package ai.agentican.blocks.llm;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;

public final class Templates {

    private static final Engine ENGINE = Engine.builder().addDefaults().build();

    private Templates() {}

    public static Template parse(String template) {

        if (template == null) return null;

        return ENGINE.parse(template);
    }

    public static String render(Template template, Object data) {

        if (template == null) return null;

        return data != null ? template.data(data).render() : template.render();
    }
}
