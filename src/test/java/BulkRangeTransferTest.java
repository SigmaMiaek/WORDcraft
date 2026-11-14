import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import com.sun.net.httpserver.HttpServer;
import pl.wordcraft.core.*;
import pl.wordcraft.download.RemoteZip;

/** Exercises the instrumented real range downloader against a local throttled ZIP server. */
public final class BulkRangeTransferTest {
    public static void main(String[] args) throws Exception {
        byte[] payload = new byte[1_000_000];
        new Random(42).nextBytes(payload);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry = new ZipEntry("clip.wmv");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            CRC32 crc = new CRC32();crc.update(payload);entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);zip.write(payload);zip.closeEntry();
        }
        byte[] archive = output.toByteArray();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService serverThreads = Executors.newCachedThreadPool();
        ExecutorService loader = Executors.newSingleThreadExecutor();
        Path root = Files.createTempDirectory("wordcraft-range-test-");
        server.setExecutor(serverThreads);
        server.createContext("/media.zip", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            String[] limits = range.substring(6).split("-");
            int start = Integer.parseInt(limits[0]), end = Integer.parseInt(limits[1]);
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + archive.length);
            exchange.getResponseHeaders().set("ETag", "\"test-zip\"");
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(206,end-start+1);
            try (OutputStream body = exchange.getResponseBody()) {
                for (int cursor=start;cursor<=end;) {
                    int amount=Math.min(8192,end-cursor+1);
                    body.write(archive,cursor,amount);body.flush();cursor+=amount;
                    if (end-start>100_000) {
                        try {Thread.sleep(10);} catch(InterruptedException ignored){Thread.currentThread().interrupt();break;}
                    }
                }
            } finally {exchange.close();}
        });
        server.start();
        try {
            RemoteZip remote = new RemoteZip(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media.zip"),root.resolve("indexes"),null);
            RemoteZip.Entry entry = remote.index().entries().getFirst();
            MediaAsset asset = new MediaAsset(entry.name(),root.resolve("clip.wmv"),entry.compressedSize(),entry.size());
            try (BulkDownloader job = new BulkDownloader(item -> CompletableFuture.supplyAsync(() -> {
                try (BulkTransfers.Scope scope = BulkTransfers.scope(item.name())) {
                    return remote.extract(entry,item.target());
                } catch (IOException error) {throw new CompletionException(error);}
            },loader))) {
                BulkTransfers.attach(job);
                job.start(List.of(asset));
                boolean live=false;
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(job.snapshot().state().busy() && System.nanoTime()<deadline) {
                    var state=job.snapshot();
                    if (state.ready()==0 && state.receivedBytes()>100_000 && state.bytesPerSecond()>0 && state.etaSeconds()>0)live=true;
                    Thread.sleep(10);
                }
                if(!live || job.snapshot().state()!=BulkDownloader.State.COMPLETE || job.snapshot().receivedBytes()<payload.length
                        || !Arrays.equals(payload,Files.readAllBytes(asset.target()))) {
                    throw new AssertionError("Live HTTP range transfer or payload verification failed: " + job.snapshot());
                }
                System.out.println("PASS: real HTTP range bytes update speed, ETA and progress before the file completes; ZIP CRC/payload preserved");
                System.out.println("ALL BULK RANGE TRANSFER TESTS PASSED");
            }
        } finally {
            BulkTransfers.attach(null);server.stop(0);serverThreads.shutdownNow();loader.shutdownNow();
            try(var paths=Files.walk(root)) {for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
