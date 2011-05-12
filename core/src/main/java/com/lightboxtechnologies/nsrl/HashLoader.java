package com.lightboxtechnologies.nsrl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat;
import org.apache.hadoop.hbase.mapreduce.KeyValueSortReducer;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

/**
 * @author Joel Uckelman
 */ 
public class HashLoader {
  static class HashLoaderMapper
            extends Mapper<LongWritable,Text,ImmutableBytesWritable,KeyValue> {

    private final LineTokenizer tok = new OpenCSVLineTokenizer();

    private final ErrorConsumer err = new ErrorConsumer() {
      public void consume(BadDataException e, long linenum) {
        System.err.println("malformed record, line " + linenum);
        e.printStackTrace();
      }
    };

    private final HashRecordProcessor h_proc = new HashRecordProcessor();

    private final Map<String,MfgData> mfg = new HashMap<String,MfgData>();
    private final Map<String,OSData> os = new HashMap<String,OSData>();
    private final Map<Integer,List<ProdData>> prod =
      new HashMap<Integer,List<ProdData>>();

    private long timestamp;
    private final byte[] size = new byte[Bytes.SIZEOF_LONG];
    private final ImmutableBytesWritable okey = new ImmutableBytesWritable();

    private static final byte[] family = { '0' };

    // common column names
    private static final byte[] sha1_col = "sha1".getBytes();
    private static final byte[] md5_col = "md5".getBytes();
    private static final byte[] crc32_col = "crc32".getBytes();
    private static final byte[] size_col = "filesize".getBytes();
    private static final byte[] nsrl_col = "NSRL".getBytes();

    private static final byte[] one = { 1 };

    @Override
    protected void setup(Context context) throws IOException {
      final Configuration conf = context.getConfiguration();

      // ensure that all mappers have the same timestamp
      try {
        timestamp = Long.parseLong(conf.get("timestamp"));
      }
      catch (NumberFormatException e) {
        throw new RuntimeException(e);
      }

      // load data for the manufacturer, os, and product maps
      final String mfg_filename  = conf.get("mfg_filename");
      final String os_filename   = conf.get("os_filename");
      final String prod_filename = conf.get("prod_filename");
 
      final FileSystem fs = FileSystem.get(conf);

      SmallTableLoader.load(
        fs, mfg_filename, mfg, os_filename, os, prod_filename, prod, tok, err
      );
    }

    protected void writeRow(byte[] key, HashData hd,
                            List<ProdData> pl, Context context)
                                     throws InterruptedException, IOException {
      okey.set(key);

      // write the crc32 column if the key is not the crc32
      if (key.length != 4) {
        context.write(
          okey, new KeyValue(key, family, crc32_col, timestamp, hd.crc32)
        );
      }

      // write the md5 column if the key is not the md5
      if (key.length != 16) {
        context.write(
          okey, new KeyValue(key, family, md5_col, timestamp, hd.md5)
        );
      }

      // write the sha1 column if the key is not the sha1
      if (key.length != 20) {
        context.write(
          okey, new KeyValue(key, family, sha1_col, timestamp, hd.sha1)
        );
      }

      // write the file size
      Bytes.putLong(size, 0, hd.size);
      context.write(
        okey, new KeyValue(key, family, size_col, timestamp, size)
      );

      // check the NSRL box
      context.write(okey, new KeyValue(key, family, nsrl_col, timestamp, one));

      // check the manufacturer/product/os box for each product
      for (ProdData pd : pl) {
        final MfgData pmd = mfg.get(pd.mfg_code);

        final byte[] set_col =
          (pmd.name + '/' + pd.name + ' ' + pd.version).getBytes();

        context.write(okey, new KeyValue(key, family, set_col, timestamp, one));
      }
    }

    @Override
    protected void map(LongWritable linenum, Text line, Context context)
                                     throws InterruptedException, IOException {
      if (line.toString().startsWith("\"SHA-1\"")) {
        // skip this line, it's the header which gives the column names
        return;
      }

      // parse the line into the hash data
      final String[] cols = tok.tokenize(line.toString());

      HashData hd = null;
      try {
        hd = h_proc.process(cols);
      }
      catch (BadDataException e) {
        err.consume(e, linenum.get());
        return;
      }

      // look up the product and OS data
      final List<ProdData> pl = prod.get(hd.prod_code);

      // create one row for each hash
      writeRow(hd.crc32, hd, pl, context);
      writeRow(hd.md5,   hd, pl, context);
      writeRow(hd.sha1,  hd, pl, context);
    }
  }

  public static void main(String[] args) throws Exception {
    final Configuration conf = new Configuration();

    final String[] otherArgs =
      new GenericOptionsParser(conf, args).getRemainingArgs();

    if (otherArgs.length != 5) {
      System.err.println(
        "Usage: HashLoader <mfgfile> <osfile> <prodfile> <hashfile> <outpath>"
      );
      System.exit(2);
    }

    final String mfg_filename    = otherArgs[0];
    final String os_filename     = otherArgs[1];
    final String prod_filename   = otherArgs[2];
    final String hash_filename   = otherArgs[3];
    final String output_filename = otherArgs[4];

    conf.set("mfg_filename", mfg_filename);
    conf.set("os_filename", os_filename);
    conf.set("prod_filename", prod_filename);

    conf.setLong("timestamp", System.currentTimeMillis());

    final Job job = new Job(conf, "HashLoader");
    job.setJarByClass(HashLoader.class);
    job.setMapperClass(HashLoaderMapper.class);
    job.setReducerClass(KeyValueSortReducer.class);
    job.setNumReduceTasks(1);

    job.setOutputKeyClass(ImmutableBytesWritable.class);
    job.setOutputValueClass(KeyValue.class);

    job.setInputFormatClass(TextInputFormat.class);
    job.setOutputFormatClass(HFileOutputFormat.class);

    TextInputFormat.addInputPath(job, new Path(hash_filename));
    HFileOutputFormat.setOutputPath(job, new Path(output_filename));

    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }
}