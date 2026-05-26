package ai.agentican.blocks.llm;

import com.fasterxml.jackson.databind.JsonNode;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;

public final class Utils {

    private static final SchemaGenerator GENERATOR;

    static {

        var configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule());

        GENERATOR = new SchemaGenerator(configBuilder.build());
    }

    private Utils() {}

    public static boolean isFound(String str) {

        return !isMissing(str);
    }

    public static boolean isMissing(String str) {

        return str == null || str.isBlank();
    }

    public static JsonNode schema(Class<?> type) {

        if (type == null) throw new IllegalArgumentException("type is required");

        return GENERATOR.generateSchema(type);
    }

    public static boolean isUnstructured(Class<?> type) {

        return type == null || type == Void.class;
    }
}
