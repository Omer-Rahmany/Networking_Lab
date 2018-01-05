
import java.io.IOException;
import java.net.*;
import java.util.Queue;
import java.util.concurrent.*;

public class IdcDm {

    /**
     * Receive arguments from the command-line, provide some feedback and start the download.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int numberOfWorkers = 1;
        Long maxBytesPerSecond = null;

        if (args.length < 1 || args.length > 3) {
            System.err.printf("usage:\n\tjava IdcDm URL [MAX-CONCURRENT-CONNECTIONS] [MAX-DOWNLOAD-LIMIT]\n");
            System.exit(1);
        } else if (args.length >= 2) {
            numberOfWorkers = Integer.parseInt(args[1]);
            if (args.length == 3)
                maxBytesPerSecond = Long.parseLong(args[2]);
        }

        String url = args[0];

        System.err.printf("Downloading");
        if (numberOfWorkers > 1)
            System.err.printf(" using %d connections", numberOfWorkers);
        if (maxBytesPerSecond != null)
            System.err.printf(" limited to %d Bps", maxBytesPerSecond);
        System.err.printf("...\n");

        DownloadURL(url, numberOfWorkers, maxBytesPerSecond);
    }

    /**
     * Initiate the file's metadata, and iterate over missing ranges. For each:
     * 1. Setup the Queue, TokenBucket, DownloadableMetadata, FileWriter, RateLimiter, and a pool of HTTPRangeGetters
     * 2. Join the HTTPRangeGetters, send finish marker to the Queue and terminate the TokenBucket
     * 3. Join the FileWriter and RateLimiter
     * <p>
     * Finally, print "Download succeeded/failed" and delete the metadata as needed.
     *
     * @param url               URL to download
     * @param numberOfWorkers   number of concurrent connections
     * @param maxBytesPerSecond limit on download bytes-per-second
     */
    private static void DownloadURL(String url, int numberOfWorkers, Long maxBytesPerSecond) {
        //TODO
        //1. Setup the Queue, TokenBucket, DownloadableMetadata, FileWriter, RateLimiter, and a pool of HTTPRangeGetters
        int fileSize = getFileSize(url);
        int chunkQueueSize = (int) Math.ceil((double)fileSize / HTTPRangeGetter.CHUNK_SIZE); //round up

        BlockingQueue<Chunk> chunkQueue = new ArrayBlockingQueue<>(chunkQueueSize);
        DownloadableMetadata downloadableMetadata = new DownloadableMetadata(url);
        FileWriter fileWriter = new FileWriter(downloadableMetadata, chunkQueue);
        Thread fileWriterThread = new Thread(fileWriter);
        fileWriterThread.start();

        TokenBucket tokenBucket = new TokenBucket();
        RateLimiter rateLimiter = new RateLimiter(tokenBucket, maxBytesPerSecond);
        Thread rateLimiterThread = new Thread(rateLimiter);
        rateLimiterThread.start();

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(numberOfWorkers);
        long startRange = 0L;
        long rangeChunkSize = fileSize / numberOfWorkers;
        long endRange = rangeChunkSize;
        for (int i = 0; i < numberOfWorkers; i++){
            startRange  += rangeChunkSize;
            endRange += rangeChunkSize;
            Range range = new Range(startRange, endRange);
            HTTPRangeGetter httpRangeGetter = new HTTPRangeGetter(url, range, chunkQueue, tokenBucket);
            executor.execute(httpRangeGetter);
        }

        // 2. Join the HTTPRangeGetters, send finish marker to the Queue and terminate the TokenBucket
        executor.shutdown();
        try {
            while (!executor.awaitTermination(24L, TimeUnit.HOURS)) {
                System.out.println("Not yet. Still waiting for termination");
            }
            chunkQueue.put(new Chunk(new byte[0], 0, 0));
            tokenBucket.terminate();

            // 3. Join the FileWriter and RateLimiter
            fileWriterThread.join();
            rateLimiterThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    private static int getFileSize(String urlString) {
        URL url = null;
        URLConnection conn = null;
        try {
            url = new URL(urlString);
            conn = url.openConnection();
            if (conn instanceof HttpURLConnection) {
                ((HttpURLConnection) conn).setRequestMethod("HEAD");
            }
            conn.getInputStream();
            return conn.getContentLength();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (conn instanceof HttpURLConnection) {
                ((HttpURLConnection) conn).disconnect();
            }
        }
        return -1;
    }
}
