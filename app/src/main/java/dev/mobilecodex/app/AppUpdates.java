package dev.mobilecodex.app;

import static dev.mobilecodex.app.core.Texts.t;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import dev.mobilecodex.app.core.UpdateCatalog;
import dev.mobilecodex.app.core.VerifiedDownload;
import static dev.mobilecodex.app.core.Json.*;

/** One app-owned operation at a time; network and APK hashing never occupy the model's work queue. */
final class AppUpdates {
    interface InstallResult { void complete(File apk, Exception error); }
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory;
    private final Consumer<JSONObject> listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile HttpURLConnection connection;
    private JSONObject candidate;
    private String status="idle", message=t("업데이트 확인을 누르면 공개 릴리스를 조회합니다.");
    private boolean busy, ready;
    private long received, revision;
    AppUpdates(Context context, Consumer<JSONObject> listener) {
        this.context=context.getApplicationContext();this.listener=listener;prefs=context.getSharedPreferences("app-updates",0);directory=new File(context.getCacheDir(),"app-updates");
        File[] partials=directory.listFiles((dir,name)->name.endsWith(".part"));
        if(partials!=null && partials.length>0){for(File partial:partials)partial.delete();message=t("이전 다운로드가 중단됐습니다. 업데이트를 다시 확인해 주세요.");}
        try {
            String saved=prefs.getString("downloaded","");
            if(!saved.isEmpty()) {
                JSONObject data=parse(saved);
                if(data.optString("sha256").matches("[a-f0-9]{64}") && data.optString("repository").equals(repository()) && UpdateCatalog.compare(data.getString("versionName"),installed().versionName)>0) {
                    candidate=data;ready=apk().isFile() && apk().length()==data.getLong("size");
                    if(ready){status="ready";message=t("다운로드한 APK가 있습니다. 설치 전에 다시 검증합니다.");}
                }
            }
        } catch(Exception ignored){candidate=null;ready=false;}
    }
    private PackageInfo installed() throws PackageManager.NameNotFoundException { return context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES); }
    private String repository(){return prefs.getString("repository","nokryong/mobile-codex");}
    private File apk(){return new File(directory,candidate.optString("sha256")+".apk");}
    synchronized JSONObject snapshot() {
        String version="";long code=0;try{PackageInfo current=installed();version=current.versionName;code=current.getLongVersionCode();}catch(Exception ignored){}
        boolean available=false;try{available=candidate!=null && UpdateCatalog.compare(candidate.getString("versionName"),version)>0;}catch(Exception ignored){}
        String[] downloads=directory.list((dir,name)->name.endsWith(".apk"));
        return obj("hasDownload",downloads!=null && downloads.length>0,"revision",revision,"available",available,"status",status,"message",t(message),"busy",busy,"ready",ready,"received",received,"candidate",candidate==null?JSONObject.NULL:parse(candidate.toString()),"repository",repository(),"prereleases",prefs.getBoolean("prereleases",true),"versionName",version,"versionCode",code,"canInstall",context.getPackageManager().canRequestPackageInstalls(),"canCancel",busy && !status.equals("installing"));
    }
    private synchronized void changed(){revision++;listener.accept(snapshot());}
    private void idle() throws IOException{if(busy)throw new IOException(t("업데이트 작업이 진행 중입니다. 완료하거나 취소한 뒤 다시 시도해 주세요."));}
    synchronized void configure(String repo,boolean prereleases)throws Exception{
        idle();repo=UpdateCatalog.repository(repo);
        if(repo.equals(repository()) && prereleases==prefs.getBoolean("prereleases",true))return;
        prefs.edit().putString("repository",repo).putBoolean("prereleases",prereleases).remove("downloaded").apply();candidate=null;ready=false;status="idle";message=t("배포 설정을 저장했습니다. 업데이트를 확인해 주세요.");changed();
    }
    private synchronized void begin(String phase,String text)throws Exception{idle();cancelled.set(false);busy=true;status=phase;message=text;received=0;changed();}
    private synchronized void failed(Exception error){busy=false;status=cancelled.get()?"cancelled":"error";message=cancelled.get()?t("업데이트 작업을 취소했습니다."):error.getMessage()==null?t("업데이트 처리에 실패했습니다. 다시 시도해 주세요."):error.getMessage();changed();}
    private void checkCancelled()throws IOException{if(cancelled.get())throw new IOException(t("취소됨"));}
    synchronized void check()throws Exception{
        begin("checking",t("새 버전을 확인하는 중입니다…"));ready=false;candidate=null;
        final String repo=repository();final boolean pre=prefs.getBoolean("prereleases",true);
        worker.execute(()->{try{
            byte[] bytes=readSmall("https://api.github.com/repos/"+repo+"/releases?per_page=20");
            JSONObject found=UpdateCatalog.latest(new JSONArray(new String(bytes,StandardCharsets.UTF_8)),repo,pre);checkCancelled();
            synchronized(this){
                busy=false;candidate=found;
                if(found==null){status="unavailable";message=t("설치 가능한 릴리스가 없습니다. ARM64 APK와 GitHub SHA-256 정보가 필요합니다.");}
                else if(UpdateCatalog.compare(found.getString("versionName"),installed().versionName)<=0){status="upToDate";message=t("조회한 공개 릴리스보다 현재 앱이 같거나 최신입니다.");}
                else{status="available";message=t("새 버전을 다운로드할 수 있습니다.");}
                changed();
            }
        }catch(Exception error){failed(error);}finally{HttpURLConnection c=connection;if(c!=null)c.disconnect();connection=null;}});
    }
    private void requireCandidate(String sha256)throws IOException{if(candidate==null || !candidate.optString("sha256").equals(sha256))throw new IOException(t("업데이트 정보가 바뀌었습니다. 다시 확인해 주세요."));}
    synchronized void download(String sha256)throws Exception{
        idle();requireCandidate(sha256);if(candidate==null || UpdateCatalog.compare(candidate.getString("versionName"),installed().versionName)<=0)throw new IOException(t("먼저 새 버전을 확인해 주세요."));
        final JSONObject selected=parse(candidate.toString());begin("downloading",t("업데이트를 다운로드하는 중입니다…"));ready=false;
        worker.execute(()->{File partial=new File(directory,UUID.randomUUID()+".part");try{
            directory.mkdirs();long size=selected.getLong("size");if(directory.getUsableSpace()<size+32L*1024*1024)throw new IOException(t("다운로드 공간이 부족합니다. 저장 공간을 확보해 주세요."));
            HttpURLConnection request=open(selected.getString("url"));long announced=request.getContentLengthLong();if(announced>=0 && announced!=size)throw new IOException(t("APK 크기가 릴리스 정보와 다릅니다."));
            long count;long[] last={0};
            try(InputStream input=request.getInputStream();OutputStream output=new FileOutputStream(partial)){
                count=VerifiedDownload.copy(input,output,size,selected.getString("sha256"),cancelled::get,bytes->{
                    if(System.currentTimeMillis()-last[0]>=250){synchronized(this){received=bytes;changed();}last[0]=System.currentTimeMillis();}
                });
            }finally{request.disconnect();connection=null;}
            synchronized(this){status="verifying";message=t("APK 버전과 서명을 확인하는 중입니다…");received=count;changed();}
            verify(partial,selected);checkCancelled();File destination=new File(directory,selected.getString("sha256")+".apk");Files.move(partial.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
            prefs.edit().putString("downloaded",selected.toString()).apply();
            synchronized(this){busy=false;ready=true;status="ready";message=t("검증을 마쳤습니다. 설치를 누르면 Android 확인 화면이 열립니다.");changed();}
        }catch(Exception error){failed(error);}finally{partial.delete();HttpURLConnection c=connection;if(c!=null)c.disconnect();connection=null;}});
    }
    synchronized void cancel(){if(busy && !status.equals("installing")){cancelled.set(true);HttpURLConnection c=connection;if(c!=null)c.disconnect();message=t("취소하는 중입니다…");changed();}}
    synchronized void clear()throws Exception{
        idle();File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.getName().matches("[a-f0-9-]+\\.(apk|part)")&&!file.delete())throw new IOException(t("다운로드 파일을 지우지 못했습니다."));
        prefs.edit().remove("downloaded").apply();ready=false;status=candidate==null?"idle":"available";message=t("다운로드한 업데이트 파일을 지웠습니다.");changed();
    }
    synchronized void prepareInstall(String sha256,InstallResult result)throws Exception{
        idle();requireCandidate(sha256);if(!ready||candidate==null)throw new IOException(t("먼저 APK를 다운로드해 주세요."));
        final JSONObject selected=parse(candidate.toString());final File file=apk();begin("verifying",t("설치 전 APK를 다시 확인하는 중입니다…"));
        worker.execute(()->{try{
            if(!file.isFile()||file.length()!=selected.getLong("size"))throw new IOException(t("다운로드한 APK가 없습니다. 다시 다운로드해 주세요."));
            try(InputStream input=new FileInputStream(file)){
                VerifiedDownload.copy(input,new OutputStream(){public void write(int value){} public void write(byte[] bytes,int offset,int length){}},selected.getLong("size"),selected.getString("sha256"),cancelled::get,bytes->{});
            }
            verify(file,selected);checkCancelled();synchronized(this){status="installing";message=t("Android 설치 화면에서 확인해 주세요.");changed();}result.complete(file,null);
        }catch(Exception error){synchronized(this){ready=false;}failed(error);result.complete(null,error);}});
    }
    synchronized void installEnded(){if(status.equals("installing")){busy=false;status="ready";message=t("설치 화면을 닫았습니다. 현재 앱 버전을 확인하세요.");changed();}}
    private void verify(File file,JSONObject selected)throws Exception{
        PackageManager manager=context.getPackageManager();PackageInfo current=installed(),next=manager.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(next==null||next.applicationInfo==null||next.signingInfo==null||current.signingInfo==null)throw new IOException(t("APK의 패키지·서명 정보를 읽지 못했습니다."));
        Set<String> history=next.signingInfo.hasMultipleSigners()?Set.of():certificates(next.signingInfo.getSigningCertificateHistory());
        UpdateCatalog.identity(context.getPackageName(),current.getLongVersionCode(),next.packageName,next.getLongVersionCode(),selected.getString("versionName"),next.versionName,next.applicationInfo.minSdkVersion,Build.VERSION.SDK_INT,UpdateCatalog.compatibleSigners(certificates(current.signingInfo.getApkContentsSigners()),certificates(next.signingInfo.getApkContentsSigners()),history));
    }
    private static Set<String> certificates(Signature[] signatures)throws Exception{Set<String> result=new HashSet<>();if(signatures!=null)for(Signature signature:signatures)result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));return result;}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private byte[] readSmall(String url)throws Exception{
        HttpURLConnection request=open(url);try(InputStream input=request.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] block=new byte[8192];int n;while((n=input.read(block))!=-1){checkCancelled();if(out.size()+n>2*1024*1024)throw new IOException(t("릴리스 응답이 너무 큽니다."));out.write(block,0,n);}return out.toByteArray();
        }finally{request.disconnect();connection=null;}
    }
    private HttpURLConnection open(String raw)throws Exception{
        URL url=new URL(raw);
        for(int redirect=0;redirect<6;redirect++){
            checkCancelled();validateHost(url);
            HttpURLConnection request=(HttpURLConnection)url.openConnection();connection=request;request.setInstanceFollowRedirects(false);request.setConnectTimeout(15000);request.setReadTimeout(30000);request.setRequestProperty("User-Agent","Mobile-Codex-Android-Updater");request.setRequestProperty("Accept",url.getHost().equals("api.github.com")?"application/vnd.github+json":"application/octet-stream");
            int code=request.getResponseCode();
            if(code>=300&&code<400){String location=request.getHeaderField("Location");request.disconnect();if(location==null)throw new IOException(t("잘못된 다운로드 이동 응답입니다."));url=new URL(url,location);continue;}
            if(code!=200){request.disconnect();throw new IOException(code==403||code==429?t("GitHub 조회 한도에 도달했거나 접근이 거부됐습니다. 잠시 뒤 다시 시도해 주세요."):code==404?t("공개 저장소 또는 릴리스 파일을 찾지 못했습니다."):t("업데이트 서버 오류 (")+code+")");}
            return request;
        }
        throw new IOException(t("다운로드 주소 이동이 너무 많습니다."));
    }
    static void validateHost(URL url)throws IOException{
        if(!url.getProtocol().equals("https")||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443)||!Set.of("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com").contains(url.getHost()))throw new IOException(t("허용되지 않은 업데이트 주소입니다."));
    }
    void shutdown(){cancel();worker.shutdownNow();}
}
