import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.TaskCounter;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import parser.ParserMapper;
import ranking.PageRankCombiner;
import ranking.PageRankMapper;
import ranking.PageRankReducer;
import sorting.Comparator;
import sorting.SortMapper;
import sorting.SortReducer;

public class Driver {
    private static String INPUT_PATH;
    private static String OUTPUT_Parsing = "OUTPUT-1";
    private static String OUTPUT_Ranking = "OUTPUT-2";
    private static String FINAL_OUTPUT = "PageRank";
    private static int NUM_REDUCERS;
    private static float ALPHA;
    private static int NUM_ITERATIONS;

    public static void main(String[] args) throws Exception {

        // set configurations
        Configuration conf = new Configuration();

        String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
        if (otherArgs.length != 4){
            printLog("ERROR", "<data> <alpha> <number iteration> <number reducers>", true);
            System.exit(-1);
        }

        deleteFile(conf, OUTPUT_Parsing);
        deleteFile(conf, OUTPUT_Ranking);
        deleteFile(conf, FINAL_OUTPUT);

        System.out.println("[Configurations]");
        System.out.println("args[0]: data <input>\t"+otherArgs[0]);
        System.out.println("args[1]: alpha <input>\t"+otherArgs[1]);
        System.out.println("args[2]: number iteration <input>\t"+otherArgs[2]);
        System.out.println("args[3]: number reducers <input>\t"+otherArgs[3]);

        INPUT_PATH = otherArgs[0];
        ALPHA = Float.parseFloat(otherArgs[1]);
        NUM_ITERATIONS = Integer.parseInt(otherArgs[2]);
        NUM_REDUCERS = Integer.parseInt(otherArgs[3]);

        if(NUM_ITERATIONS < 1){
            System.err.println("[Error] -> Iteration must be greater than 0");
            System.exit(-1);
        } else if (NUM_REDUCERS < 1){
            System.err.println("[Error] -> Reducers must be greater than 0");
            System.exit(-1);
        }

        final long startExecution = System.currentTimeMillis();

        System.out.println("--------------------- START COMPUTATION -----------------------");

        //************************************** FIRST JOB ********************************************
        long numpages = parserJob(conf);
        if (numpages < 0){
            System.err.println("[ERROR] -> Something wrong in parser phase!");
            System.exit(-1);
        }

        //set the pageCount on the configuration
        conf.setLong("page.num", numpages);
        conf.setFloat("page.alpha", ALPHA);

        printLog("INFO", "Parsing completed", false);

        //************************************** SECOND JOB ********************************************
        for (int i = 0; i < NUM_ITERATIONS; i++){
            if(!computePageRankJob(conf, i)){
                printLog("ERROR", "Something wrong in compute phase", true);
                System.exit(-1);
            }
        }

        printLog("INFO", "Computing completed", false);

        //************************************** THIRD JOB ********************************************
        if (!sortJob(conf)){
            printLog("ERROR", "Something wrong in sort phase", true);
            System.exit(-1);
        }

        printLog("INFO", "Sort completed", false);
        System.out.println("--------------------- END COMPUTATION -----------------------");

        final long endExecution = System.currentTimeMillis();
        float execTimeSec = ((float)(endExecution - startExecution))/1000L;

        System.out.println("\n-------------------------- Performance ---------------------------- \n");
        printLog("EXECUTION TIME", execTimeSec + " sec", false);
    }

    private static long parserJob(Configuration conf) throws Exception{
        Job job = Job.getInstance(conf, "parser");
        job.setJarByClass(Driver.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        job.setMapperClass(ParserMapper.class);
        //job.setReducerClass(ParserReducer.class);

        // set number of reducer tasks to be used
        job.setNumReduceTasks(1);

        FileInputFormat.addInputPath(job, new Path(INPUT_PATH));
        FileOutputFormat.setOutputPath(job, new Path(OUTPUT_Parsing));

        boolean check = job.waitForCompletion(true);
        if (check)
            return job.getCounters().findCounter(TaskCounter.MAP_OUTPUT_RECORDS).getValue();
        else
            return -1;
    }

    private static boolean computePageRankJob(Configuration conf, int iteration) throws Exception{

        printLog("ITERATION", iteration+"", false);
        Job job = Job.getInstance(conf, "compute");
        job.setJarByClass(Driver.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        job.setMapperClass(PageRankMapper.class);
        job.setReducerClass(PageRankReducer.class);
        job.setCombinerClass(PageRankCombiner.class);

        // set number of reducer tasks to be used
        job.setNumReduceTasks(NUM_REDUCERS);

        // CHECK IF STEP 1
        if (iteration == 0) {
            FileInputFormat.setInputPaths(job, new Path(OUTPUT_Parsing + "/part-r-00000"));
            FileOutputFormat.setOutputPath(job, new Path(OUTPUT_Ranking + "/iter" + (iteration+1)));
        } else {
            /*
            FileInputFormat.setInputPaths(job,
                    new Path(OUTPUT_Ranking + "/iter" + (iteration) + "/part-r-00000"),
                    new Path(OUTPUT_Ranking + "/iter" + (iteration) + "/part-r-00001"),
                    new Path(OUTPUT_Ranking + "/iter" + (iteration) + "/part-r-00002"));

             */

            FileInputFormat.setInputPaths(job, generatePaths(OUTPUT_Ranking + "/iter" + iteration));
            FileOutputFormat.setOutputPath(job, new Path(OUTPUT_Ranking + "/iter" + (iteration+1)));
        }


        boolean result = job.waitForCompletion(true);
        /*
        if (iteration == 0)
            deleteFile(conf, OUTPUT_Parsing);
        if (iteration > 0)
            deleteFile(conf, OUTPUT_Ranking + "/iter" + iteration);

         */

        return result;
    }

    private static boolean sortJob(Configuration conf) throws Exception{
        Job job = Job.getInstance(conf, "sort");
        job.setJarByClass(Driver.class);

        job.setMapOutputKeyClass(Comparator.class);
        job.setMapOutputValueClass(NullWritable.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        job.setMapperClass(SortMapper.class);
        job.setReducerClass(SortReducer.class);

        // set number of reducer tasks to be used
        job.setNumReduceTasks(1);

        /*
        FileInputFormat.setInputPaths(job,  new Path(OUTPUT_Ranking + "/iter" + NUM_ITERATIONS + "/part-r-00000"),
                new Path(OUTPUT_Ranking + "/iter" + (NUM_ITERATIONS) + "/part-r-00001"),
                new Path(OUTPUT_Ranking + "/iter" + (NUM_ITERATIONS) + "/part-r-00002"));
         */

        FileInputFormat.setInputPaths(job, generatePaths(OUTPUT_Ranking + "/iter" + NUM_ITERATIONS));

        FileOutputFormat.setOutputPath(job, new Path(FINAL_OUTPUT));

        return job.waitForCompletion(true);
    }

    //*********************************UTILITY**************************************/
    static Path[] generatePaths(String root){
        Path[] paths = new Path[NUM_REDUCERS];
        for(int p = 0; p<NUM_REDUCERS; p++)
            paths[p] = new Path(root + "/part-r-0000" + p);
        return paths;
    }

    //  removes old outputs that has to be overwritten by hadoop jobs
    private static void deleteFile(Configuration conf, String path) throws Exception{
        Path filePath = new Path(path);
        FileSystem fs = filePath.getFileSystem(conf);
        if (fs.exists(filePath))
            fs.delete(filePath, true);
    }

    private static void printLog(String type, String text, boolean error){
        if (!error)
            System.out.println("\n["+type+"]\t->\t" + text + ".\n");
        else
            System.err.println("\n["+type+"]\t->\t" + text + ".\n");
    }
}
