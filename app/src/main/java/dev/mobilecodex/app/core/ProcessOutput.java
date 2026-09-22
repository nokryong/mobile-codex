package dev.mobilecodex.app.core;

import static dev.mobilecodex.app.core.Texts.t;
import java.io.*;
import java.util.concurrent.*;

/** Bounded stdout and stderr drains: no shell interpolation, no pipe deadlock, no unlimited wait. */
public final class ProcessOutput {
    private ProcessOutput() {}
    public static byte[] run(ProcessBuilder builder, int limit, int timeoutSeconds) throws Exception {
        Process process = builder.start();
        FutureTask<byte[]> output = new FutureTask<>(() -> read(process.getInputStream(), limit));
        FutureTask<byte[]> error = new FutureTask<>(() -> read(process.getErrorStream(), 65536));
        Thread out = new Thread(output, "git-output"), err = new Thread(error, "git-errors"); out.setDaemon(true); err.setDaemon(true); out.start(); err.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw new IOException(t("Git 확인 시간이 초과되었습니다."));
            byte[] bytes = output.get(1, TimeUnit.SECONDS); error.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IOException(t("Git 조회에 실패했습니다. 저장소 여부와 파일 접근 권한을 확인해 주세요."));
            return bytes;
        } finally { process.destroyForcibly(); process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close(); output.cancel(true); error.cancel(true); }
    }
    private static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int n; boolean overflow = false;
        while ((n = in.read(block)) != -1) { if (out.size() + n <= limit) out.write(block, 0, n); else overflow = true; }
        if (overflow) throw new IOException(t("Git 출력이 너무 큽니다. 터미널에서 파일별로 확인해 주세요."));
        return out.toByteArray();
    }
}
