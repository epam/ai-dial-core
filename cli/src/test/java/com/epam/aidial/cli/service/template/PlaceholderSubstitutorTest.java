package com.epam.aidial.cli.service.template;

import com.epam.aidial.cli.exception.TemplateException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderSubstitutorTest {

    private static Map<String, Object> baseCtx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(TemplateResolver.NS_VARS, new HashMap<String, Object>());
        ctx.put(TemplateResolver.NS_PARAMS, new HashMap<String, Object>());
        ctx.put(TemplateResolver.NS_ENTITY, new HashMap<String, Object>());
        return ctx;
    }

    private static Function<String, String> envFrom(Map<String, String> map) {
        return map::get;
    }

    @Test
    void secretHappyPath() {
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(baseCtx(),
                envFrom(Map.of("openai-key", "sk-xyz")));
        assertEquals("sk-xyz", sub.substitute("${SECRET:openai-key}"));
    }

    @Test
    void secretMissingFailsLoud() {
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(baseCtx(),
                envFrom(Map.of()));
        TemplateException te = assertThrows(TemplateException.class,
                () -> sub.substitute("${SECRET:openai-key}"));
        assertTrue(te.getMessage().contains("openai-key"),
                () -> "expected message to name the missing key, got: " + te.getMessage());
    }

    @Test
    void envVarFallbackHappyPath() {
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(baseCtx(),
                envFrom(Map.of("OPENAI_TOKEN", "tok-123")));
        assertEquals("tok-123", sub.substitute("${OPENAI_TOKEN}"));
    }

    @Test
    void envVarFallbackMissingFailsLoud() {
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(baseCtx(),
                envFrom(Map.of()));
        TemplateException te = assertThrows(TemplateException.class,
                () -> sub.substitute("${MISSING}"));
        assertTrue(te.getMessage().contains("MISSING"),
                () -> "expected message to name the missing identifier, got: " + te.getMessage());
    }

    @Test
    void forBindingShadowsEnv() {
        // Loop binding is placed at the top level of ctx — it must shadow shell env.
        Map<String, Object> ctx = baseCtx();
        ctx.put("region", "us-east-1");
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(ctx,
                envFrom(Map.of("region", "shell-value")));
        assertEquals("us-east-1", sub.substitute("${region}"));
    }

    @Test
    void varsBeatsBareEnv() {
        // Multi-segment ${vars.X} never falls through to env, even if vars.X is missing —
        // unknown-namespace and missing-value paths keep their existing semantics.
        Map<String, Object> ctx = baseCtx();
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) ctx.get(TemplateResolver.NS_VARS);
        vars.put("X", "from-vars");
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(ctx,
                envFrom(Map.of("X", "from-env")));
        assertEquals("from-vars", sub.substitute("${vars.X}"));
    }

    @Test
    void multiSegmentUnknownNamespaceStillThrows() {
        // Env fallback is single-segment-only — multi-segment paths with an unknown
        // namespace must still surface as a clear "Unknown placeholder namespace" error.
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(baseCtx(),
                envFrom(Map.of("ZZZ", "x")));
        TemplateException te = assertThrows(TemplateException.class,
                () -> sub.substitute("${ZZZ.x}"));
        assertTrue(te.getMessage().contains("namespace"),
                () -> "expected unknown-namespace error, got: " + te.getMessage());
    }

    @Test
    void base64FunctionResolvesSecretArg() {
        // ${base64(SECRET:foo)} — the function-call branch in
        // resolveExpression dispatches arg parsing to parseArgs → resolvePath, which catches
        // SECRET: at its top; the resolved value is then base64-encoded by FunctionApplicator.
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(baseCtx(),
                envFrom(Map.of("foo", "bar")));
        // base64('bar') == 'YmFy'
        assertEquals("YmFy", sub.substitute("${base64(SECRET:foo)}"));
    }

    @Test
    void interpolationMixesEnvAndLiteral() {
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(baseCtx(),
                envFrom(Map.of("HOST", "localhost", "PORT", "8080")));
        assertEquals("http://localhost:8080/api",
                sub.substitute("http://${HOST}:${PORT}/api"));
    }

    @Test
    void defaultConstructorUsesSystemEnv() {
        // Sanity: with no explicit resolver, the default constructor uses System::getenv.
        // We can't set env vars from a unit test portably, but PATH is universally set.
        PlaceholderSubstitutor sub = new PlaceholderSubstitutor(baseCtx());
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return; // skip on the rare environment where PATH isn't set
        }
        assertEquals(path, sub.substitute("${PATH}"));
    }
}
