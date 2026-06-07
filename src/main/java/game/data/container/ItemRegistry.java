package game.data.container;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import game.data.chunk.palette.BlockRegistry;
import game.data.registries.RegistriesJson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class ItemRegistry {
    private Map<Integer, String> idToName;
    private Map<String, Integer> nameToId;

    public static ItemRegistry fromJson(String version) {
        InputStream x = BlockRegistry.class.getClassLoader().getResourceAsStream("items-" + version + ".json");
        if (x == null) {
            return null;
        }
        // The bundled legacy registry is {"items": {"<numeric id>": "<name>"}}; parse it into both maps.
        // (Deserializing straight into this class would not work: the field names don't match the JSON.)
        ItemRegistry registry = new ItemRegistry();
        JsonObject root = JsonParser.parseReader(new InputStreamReader(x)).getAsJsonObject();
        JsonObject items = root.getAsJsonObject("items");
        if (items != null) {
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                try {
                    int id = Integer.parseInt(entry.getKey());
                    String name = entry.getValue().getAsString();
                    registry.idToName.put(id, name);
                    registry.nameToId.put(name, id);
                } catch (NumberFormatException ignored) {
                    // skip non-numeric keys
                }
            }
        }
        return registry;
    }

    public static ItemRegistry fromRegistry(InputStream input) {
        if (input == null) { return new ItemRegistry(); }

        RegistriesJson map = new Gson().fromJson(new InputStreamReader(input), RegistriesJson.class);

        // convert JSON structure into protocol_id->name map
        ItemRegistry itemRegistry = new ItemRegistry();
        map.get("minecraft:item").getEntries().forEach(
            (name, properties) -> {
                itemRegistry.idToName.put(properties.get("protocol_id"), name);
                itemRegistry.nameToId.put(name, properties.get("protocol_id"));
            }
        );

        return itemRegistry;
    }

    public ItemRegistry() {
        idToName = new HashMap<>();
        nameToId = new HashMap<>();
    }

    public String getItemName(int protocolId) {
        return idToName.get(protocolId);
    }
    public int getItemId(String name) {
        return nameToId.get(name);
    }
}
