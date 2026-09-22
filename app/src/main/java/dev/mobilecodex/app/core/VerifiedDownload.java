package dev.mobilecodex.app.core;

import static dev.mobilecodex.app.core.Texts.t;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.*;

public final class VerifiedDownload {
    private VerifiedDownload() {}
    public static long copy(InputStream input, OutputStream output, long size, String sha256, BooleanSupplier cancelled, LongConsumer progress) throws Exception {
        if(size<=0 || !sha256.matches("[a-f0-9]{64}"))throw new IOException(t("잘못된 다운로드 검증 정보입니다."));
        MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];long count=0;int n;
        while(true){
            if(cancelled.getAsBoolean())throw new IOException(t("취소됨"));
            n=input.read(block);if(n==-1)break;
            count+=n;if(count>size)throw new IOException(t("APK가 표시된 크기보다 큽니다."));
            hash.update(block,0,n);output.write(block,0,n);progress.accept(count);
        }
        StringBuilder actual=new StringBuilder();for(byte b:hash.digest())actual.append(String.format(Locale.ROOT,"%02x",b&255));
        if(count!=size || !actual.toString().equals(sha256))throw new IOException(t("APK 다운로드 검증에 실패했습니다. 다시 다운로드해 주세요."));
        return count;
    }
}
