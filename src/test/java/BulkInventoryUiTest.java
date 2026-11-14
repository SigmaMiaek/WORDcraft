import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import pl.wordcraft.core.*;
import pl.wordcraft.download.*;

public final class BulkInventoryUiTest {
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("wordcraft-inventory-test-");
        try {
            OfficialRepository repository=new OfficialRepository(root,null);
            Class<?> source=Class.forName("pl.wordcraft.download.OfficialRepository$MediaSource");
            var sourceConstructor=source.getDeclaredConstructors()[0];sourceConstructor.setAccessible(true);
            RemoteZip remote=new RemoteZip(java.net.URI.create("http://127.0.0.1/not-requested"),root,null);
            var a=new RemoteZip.Entry("a.jpg",60,100,0,0,0,0,0);
            var b=new RemoteZip.Entry("b.wmv",700,1000,0,0,0,0,0);
            Object first=sourceConstructor.newInstance(remote,a,root.resolve("a.jpg"));
            Object second=sourceConstructor.newInstance(remote,b,root.resolve("b.wmv"));
            set(repository,"mediaSources",Map.of("A.jpg",first,"a.jpg",first,"b.wmv",second));
            var inventory=repository.mediaInventory();
            check(inventory.size()==2,"Aliased media files counted twice");
            check(inventory.stream().mapToLong(MediaAsset::diskBytes).sum()==1100,"Warning uses compressed size instead of actual disk size");
            check(inventory.stream().mapToLong(MediaAsset::downloadBytes).sum()==760,"Transfer estimate is not based on ZIP compressed sizes");
            Class<?> unsafe=Class.forName("sun.misc.Unsafe");
            var allocatorField=unsafe.getDeclaredField("theUnsafe");allocatorField.setAccessible(true);
            Object allocator=allocatorField.get(null);
            Class<?> client=Class.forName("pl.wordcraft.client.WordcraftClient");
            Object app=unsafe.getMethod("allocateInstance",Class.class).invoke(allocator,client);
            Class<?> configSource=Class.forName("pl.wordcraft.client.WordcraftClient$Source");
            Object config=configSource.getConstructor(String.class,String.class,boolean.class,int.class)
                    .newInstance("catalog.xlsx",root.toString(),true,1);
            set(app,"source",config);set(app,"directory",root);set(app,"repository",repository);
            Class<?> page=Class.forName("pl.wordcraft.client.BulkDownloadScreen");
            var constructor=page.getDeclaredConstructor(client,net.minecraft.client.gui.screens.Screen.class);
            constructor.setAccessible(true);
            Class<?> minecraft=Class.forName("net.minecraft.client.Minecraft");
            Field activeClient=minecraft.getDeclaredField("instance");activeClient.setAccessible(true);
            Object previousClient=activeClient.get(null);
            Object fakeClient=unsafe.getMethod("allocateInstance",Class.class).invoke(allocator,minecraft);
            Object screen;
            try {
                activeClient.set(null,fakeClient);
                screen=constructor.newInstance(app,null);
            } finally {activeClient.set(null,previousClient);}
            Field disk=page.getDeclaredField("diskBytes");disk.setAccessible(true);
            check(disk.getLong(screen)==1100,"UI disk-space warning does not match inventory");
            Field manager=page.getDeclaredField("downloader");manager.setAccessible(true);
            BulkDownloader job=(BulkDownloader)manager.get(screen);
            check(job.snapshot().state()==BulkDownloader.State.IDLE,"Opening the experimental page started a download without confirmation");
            Class<?> feature=Class.forName("pl.wordcraft.client.BulkMediaFeature");
            Path folder=(Path)feature.getMethod("folder",client).invoke(null,app);
            check(folder.equals(root.toAbsolutePath().normalize()),"Folder button targets a different directory");
            Class.forName("pl.wordcraft.client.SettingsScreen").getDeclaredMethods();
            feature.getDeclaredMethods();page.getDeclaredMethods();
            job.close();BulkTransfers.attach(null);
            System.out.println("PASS: actual inventory deduplicates files and separates download size from disk space");
            System.out.println("PASS: experimental page shows the actual disk size and does not start until requested");
            System.out.println("PASS: folder location and new Minecraft/Fabric UI method linkage verified");
            if(args.length>0) {
                OfficialRepository actual=new OfficialRepository(Path.of(args[0]),null);
                actual.prepare(false);
                var all=actual.mediaInventory();
                System.out.println("REAL INVENTORY: "+all.size()+" files; disk="+all.stream().mapToLong(MediaAsset::diskBytes).sum()
                        +" bytes; transfer="+all.stream().mapToLong(MediaAsset::downloadBytes).sum()+" bytes");
            }
            System.out.println("ALL BULK INVENTORY/UI TESTS PASSED");
        } finally {
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
