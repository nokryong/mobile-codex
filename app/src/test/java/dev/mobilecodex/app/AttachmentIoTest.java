package dev.mobilecodex.app;

import android.app.Application;
import android.graphics.Bitmap;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Random;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AttachmentIoTest {
    static class Counts { long read, written; }
    private static InputStream count(InputStream input, Counts counts) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException { int value=in.read(); if(value!=-1)counts.read++; return value; }
            @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);if(n>0)counts.read+=n;return n;}
        };
    }
    private static OutputStream count(OutputStream output, Counts counts) {
        return new OutputStream() {
            @Override public void write(int value)throws IOException{output.write(value);counts.written++;}
            @Override public void write(byte[]b,int off,int len)throws IOException{output.write(b,off,len);counts.written+=len;}
            @Override public void close()throws IOException{output.close();}
        };
    }
    private class MeasuredImages extends ImageStore {
        final Counts io=new Counts(); final boolean legacy;
        MeasuredImages(boolean legacy){super(RuntimeEnvironment.getApplication());this.legacy=legacy;}
        @Override protected InputStream readFile(File file)throws IOException{return count(super.readFile(file),io);}
        @Override protected OutputStream writeFile(File file)throws IOException{return count(super.writeFile(file),io);}
        @Override JSONObject importFile(File file)throws Exception{
            if(!legacy)return super.importFile(file);
            try(InputStream input=readFile(file)){return store(input,file.getName());}
        }
    }
    private JSONObject measure(byte[] bytes,String name,boolean legacy)throws Exception{
        Counts io=new Counts(); MeasuredImages images=new MeasuredImages(legacy);
        AttachmentStore store=new AttachmentStore(RuntimeEnvironment.getApplication(),images){
            @Override protected OutputStream writeFile(File file)throws IOException{return count(super.writeFile(file),io);}
        };
        long began=System.nanoTime();
        JSONObject result=store.store(count(new ByteArrayInputStream(bytes),io),name,"image/png"); // Deliberately false MIME for documents.
        double elapsed=(System.nanoTime()-began)/1_000_000.0;
        assertArrayEquals(bytes,Files.readAllBytes(new File(result.getString("path")).toPath()));
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));
        assertEquals(hash.toString(),result.getString("sha256"));
        if(!name.equals("large-image.bin")){
            assertFalse(result.has("image"));
            assertEquals(legacy?bytes.length:0,images.io.written);
            if(!legacy)assertTrue(images.io.read<=65536);
        }else{assertEquals(3000,result.getJSONObject("image").getInt("width"));assertEquals(2000,result.getJSONObject("image").getInt("height"));}
        System.out.println("ATTACHMENT_BENCH "+new JSONObject().put("name",name).put("legacy",legacy).put("bytes",bytes.length)
            .put("read",io.read+images.io.read).put("written",io.written+images.io.written).put("elapsedMs",elapsed));
        return result;
    }
    @Test public void documentsAvoidFullRecopyAndLargeImagesKeepOriginalBytes()throws Exception{
        byte[] document=new byte[8*1024*1024];new Random(7).nextBytes(document);
        for(String name:new String[]{"large.pdf","large.zip"}){
            byte[] signature=name.endsWith("pdf")?"%PDF-1.7\n".getBytes():new byte[]{'P','K',3,4};
            System.arraycopy(signature,0,document,0,signature.length);
            measure(document,name,true);measure(document,name,false);
        }
        Bitmap bitmap=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888);
        Random random=new Random(8);int[] row=new int[3000];
        for(int y=0;y<2000;y++){for(int x=0;x<3000;x++)row[x]=0xff000000|random.nextInt(0x1000000);bitmap.setPixels(row,0,3000,0,y,3000,1);}
        ByteArrayOutputStream output=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));bitmap.recycle();
        byte[] image=output.toByteArray();measure(image,"large-image.bin",true);measure(image,"large-image.bin",false);
    }
    @Test public void unknownSizeCancellationAndSpaceFailureLeaveNoPartialFiles()throws Exception{
        File directory=new File(RuntimeEnvironment.getApplication().getFilesDir(),"attachments");directory.mkdirs();int before=directory.list().length;
        ImageStore images=new ImageStore(RuntimeEnvironment.getApplication());
        AttachmentStore noSpace=new AttachmentStore(RuntimeEnvironment.getApplication(),images){
            int calls;@Override protected long availableBytes(File folder){return ++calls==1?Long.MAX_VALUE:0;}
        };
        assertThrows(IOException.class,()->noSpace.store(new ByteArrayInputStream(new byte[100000]),"unknown.pdf",null));
        assertEquals(before,directory.list().length);
        AtomicBoolean cancelled=new AtomicBoolean();
        InputStream unknown=new ByteArrayInputStream(new byte[100000]){
            @Override public int available(){return 0;}
            @Override public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);cancelled.set(true);return n;}
        };
        AttachmentStore store=new AttachmentStore(RuntimeEnvironment.getApplication(),images);
        assertThrows(InterruptedIOException.class,()->store.store(unknown,"unknown.zip",null,cancelled::get));
        assertEquals(before,directory.list().length);
    }
}
