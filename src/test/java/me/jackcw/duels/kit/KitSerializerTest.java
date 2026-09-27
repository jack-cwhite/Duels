package me.jackcw.duels.kit;

import me.jackcw.jcore.serialization.SerializerManager;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitSerializerTest
{
    private KitSerializer serializer;

    @BeforeEach
    void setup()
    {
        MockBukkit.mock();
        serializer = new KitSerializer(new SerializerManager());
    }

    @AfterEach
    void cleanup()
    {
        MockBukkit.unmock();
    }

    @Test
    void oldKitWithoutEffectsLoadsAndDoesNotAcquireAnEffectsField()
    {
        Kit kit = serializer.deserialize(2, Map.of("name", "Classic"));

        assertTrue(kit.getEffects().isEmpty());
        assertFalse(((Map<?, ?>) serializer.serialize(kit)).containsKey("effects"));
    }

    @Test
    void levelsAndAllAppearanceFlagsRoundTrip()
    {
        Kit kit = new Kit(4, "Scout");
        kit.setEffect(new KitEffect(NamespacedKey.minecraft("speed"), 1, false, true, true));
        kit.setEffect(new KitEffect(NamespacedKey.minecraft("weakness"), 2, true, false, true));
        kit.setEffect(new KitEffect(NamespacedKey.minecraft("night_vision"), 5, true, true, false));
        kit.setEffect(new KitEffect(NamespacedKey.minecraft("resistance"), 255, false, false, false));

        Kit restored = serializer.deserialize(4, serializer.serialize(kit));

        assertEquals(kit.getEffects(), restored.getEffects());
        assertEquals("minecraft:speed", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) serializer.serialize(kit)).get("effects")).getFirst()).get("type"));
    }

    @Test
    void effectsSurviveSavingAndLoadingYamlText() throws Exception
    {
        Kit kit = new Kit(4, "Scout");
        kit.setEffect(new KitEffect(NamespacedKey.minecraft("speed"), 2, false, true, false));

        YamlConfiguration written = new YamlConfiguration();
        written.set("kits.4", serializer.serialize(kit));
        String yaml = written.saveToString();
        assertTrue(yaml.contains("minecraft:speed"));

        YamlConfiguration read = new YamlConfiguration();
        read.loadFromString(yaml);
        ConfigurationSection savedKit = read.getConfigurationSection("kits.4");
        Kit restored = serializer.deserialize(4, Map.of(
                "name", savedKit.getString("name"),
                "effects", savedKit.getList("effects")));

        assertEquals(kit.getEffects(), restored.getEffects());
    }

    @Test
    void unknownAndInstantEffectTypesAreRejectedWithContext()
    {
        assertInvalid("type", "minecraft:not_an_effect", 1, false, true, true);
        assertInvalid("type", "minecraft:instant_health", 1, false, true, true);
        assertInvalid("type", "bad key", 1, false, true, true);
    }

    @Test
    void invalidLevelsAndFieldTypesAreRejectedWithContext()
    {
        assertInvalid("level", "minecraft:speed", 0, false, true, true);
        assertInvalid("level", "minecraft:speed", 256, false, true, true);
        assertInvalid("level", "minecraft:speed", "2", false, true, true);
        assertInvalid("ambient", "minecraft:speed", 1, "false", true, true);
        assertInvalid("particles", "minecraft:speed", 1, false, 1, true);
        assertInvalid("icon", "minecraft:speed", 1, false, true, null);
        assertInvalid("type", 42, 1, false, true, true);
    }

    @Test
    void duplicatesAreRejectedRatherThanSilentlyOverwriting()
    {
        Map<String, Object> effect = effect("minecraft:speed", 1, false, true, true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> serializer.deserialize(9, Map.of("name", "Scout", "effects", List.of(effect, effect))));

        assertTrue(error.getMessage().contains("Kit 9 effects[1].type"));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void effectCollectionAndEntryFieldsMustHaveTheExpectedShape()
    {
        IllegalArgumentException collection = assertThrows(IllegalArgumentException.class,
                () -> serializer.deserialize(9, Map.of("name", "Scout", "effects", "speed")));
        assertTrue(collection.getMessage().contains("Kit 9 effects"));

        IllegalArgumentException field = assertThrows(IllegalArgumentException.class,
                () -> serializer.deserialize(9, Map.of("name", "Scout", "effects", List.of(
                        Map.of("type", "minecraft:speed", "level", 1, "ambient", false,
                                "particles", true, "icon", true, "duration", 100)))));
        assertTrue(field.getMessage().contains("Kit 9 effects[0]"));
        assertTrue(field.getMessage().contains("duration"));
    }

    private void assertInvalid(String field, Object type, Object level, Object ambient, Object particles, Object icon)
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> serializer.deserialize(9, Map.of("name", "Scout", "effects", List.of(
                        effect(type, level, ambient, particles, icon)))));
        assertTrue(error.getMessage().contains("Kit 9 effects[0]." + field), error.getMessage());
    }

    private Map<String, Object> effect(Object type, Object level, Object ambient, Object particles, Object icon)
    {
        Map<String, Object> effect = new java.util.LinkedHashMap<>();
        effect.put("type", type);
        effect.put("level", level);
        effect.put("ambient", ambient);
        effect.put("particles", particles);
        effect.put("icon", icon);
        return effect;
    }
}
