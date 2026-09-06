package dev.n624.internalfurnace.forge;

import com.google.gson.*;
import dev.n624.internalfurnace.core.Rules;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The same bounded schema is used by the GUI, packets and persistent settings. */
public final class RuleCodec {
    private RuleCodec() {}
    public static Rules.Settings read(String text, int tier) {
        if (text == null || text.length() > 4096) throw new IllegalArgumentException("Rules exceed 4096 characters");
        // Prevent deeply nested JSON from exhausting the parser stack before schema validation.
        int depth=0; boolean quoted=false, escaped=false;
        for (char c : text.toCharArray()) {
            if (escaped) { escaped=false; continue; }
            if (quoted && c=='\\') { escaped=true; continue; }
            if (c=='\"') { quoted=!quoted; continue; }
            if (!quoted) {
                if (c=='[' || c=='{') { if (++depth>8) throw new IllegalArgumentException("Rules nesting too deep"); }
                if (c==']' || c=='}') { if (--depth<0) throw new IllegalArgumentException("Invalid JSON"); }
            }
        }
        if (depth!=0 || quoted) throw new IllegalArgumentException("Invalid JSON");
        JsonObject root=JsonParser.parseString(text).getAsJsonObject();
        keys(root,Set.of("enabled","allowNamed","allowEnchanted","rules"));
        List<Rules.Rule> rules=new ArrayList<>();
        JsonArray array=root.has("rules")?root.getAsJsonArray("rules"):new JsonArray();
        if(array.size()>16)throw new IllegalArgumentException("Too many rules");
        for(JsonElement element:array) {
            JsonObject r=element.getAsJsonObject(); keys(r,Set.of("action","any"));
            List<List<Rules.Condition>> any=new ArrayList<>();
            JsonArray groups=r.getAsJsonArray("any");
            if(groups.size()>8)throw new IllegalArgumentException("Too many groups");
            for(JsonElement group:groups) {
                List<Rules.Condition> conditions=new ArrayList<>();
                if(group.getAsJsonArray().size()>8)throw new IllegalArgumentException("Too many conditions");
                for(JsonElement element2:group.getAsJsonArray()) {
                    JsonObject c=element2.getAsJsonObject();keys(c,Set.of("field","value"));
                    conditions.add(new Rules.Condition(Rules.Field.valueOf(c.get("field").getAsString().toUpperCase(Locale.ROOT)),c.get("value").getAsString()));
                }
                any.add(conditions);
            }
            rules.add(new Rules.Rule(Rules.Action.valueOf(r.get("action").getAsString().toUpperCase(Locale.ROOT)),any));
        }
        Rules.Settings settings=new Rules.Settings(flag(root,"enabled"),flag(root,"allowNamed"),flag(root,"allowEnchanted"),rules);
        settings.validate(tier);
        return settings;
    }
    private static boolean flag(JsonObject obj,String name) {
        if(!obj.has(name))return false;
        if(!obj.get(name).isJsonPrimitive() || !obj.getAsJsonPrimitive(name).isBoolean())throw new IllegalArgumentException("Expected boolean: "+name);
        return obj.get(name).getAsBoolean();
    }
    private static void keys(JsonObject object,Set<String> allowed) {
        if(!allowed.containsAll(object.keySet()))throw new IllegalArgumentException("Unknown rule field");
    }
    public static String write(Rules.Settings settings) {
        JsonObject root=new JsonObject();root.addProperty("enabled",settings.enabled());
        root.addProperty("allowNamed",settings.allowNamed());root.addProperty("allowEnchanted",settings.allowEnchanted());
        JsonArray array=new JsonArray();
        for(Rules.Rule rule:settings.rules()) {
            JsonObject object=new JsonObject();object.addProperty("action",rule.action().name());JsonArray any=new JsonArray();
            for(var group:rule.any()) {
                JsonArray all=new JsonArray();
                for(var condition:group){JsonObject c=new JsonObject();c.addProperty("field",condition.field().name());c.addProperty("value",condition.value());all.add(c);}
                any.add(all);
            }
            object.add("any",any);array.add(object);
        }
        root.add("rules",array);return root.toString();
    }
}
