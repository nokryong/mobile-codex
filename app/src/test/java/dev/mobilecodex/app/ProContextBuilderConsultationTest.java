package dev.mobilecodex.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProContextBuilderConsultationTest {
    @Test public void consultationCarriesOnlyTheExactCodexPromptWithoutImplicitContextOrPluginRestrictions() throws Exception {
        String prompt = "  다음 코드의 경합을 검토해 주세요.\n연결된 플러그인에서 필요한 자료도 찾아 주세요.\n"
            + "API_KEY=example-source-line\n  ";
        JSONObject prepared = ProContextBuilder.buildConsultation(prompt);
        assertEquals(prompt, prepared.getString("prompt"));
        assertEquals(ProContextBuilder.MODEL_ID, prepared.getString("requestedModel"));
        assertEquals(0, prepared.getJSONArray("uploads").length());
        assertEquals(prompt.getBytes(StandardCharsets.UTF_8).length, prepared.getInt("contextBytes"));
        assertFalse(prepared.has("tree"));
        assertFalse(prepared.has("history"));
        assertFalse(prepared.has("instructions"));
        assertFalse(prepared.has("excluded"));
        assertEquals(prepared.getString("contextHash"), ProContextBuilder.buildConsultation(prompt).getString("contextHash"));
    }

    @Test public void blankAndOversizedConsultationsFailWithoutTruncatingTheQuestion() throws Exception {
        for (String prompt : new String[]{null, " \n\t", "x".repeat(50_001)})
            assertThrows(IOException.class, () -> ProContextBuilder.buildConsultation(prompt));
        assertEquals(50_000, ProContextBuilder.buildConsultation("x".repeat(50_000)).getString("prompt").length());
    }
}
