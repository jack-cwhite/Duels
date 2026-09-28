package me.jackcw.duels.reward;

import me.jackcw.jcore.item.ItemStackParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads rewards.yml into a {@link RewardTable}, refusing to guess.
 *
 * <p>Two deliberately different severities:
 *
 * <ul>
 *   <li><b>Errors</b> reject the whole file and leave rewards switched off. Anything
 *       that could make Duels pay an amount nobody wrote - an unknown key that is
 *       really a typo of a real one, a negative payout, the same arena configured
 *       twice - is an error, because silently paying the wrong amount is far worse
 *       than paying nothing and saying so loudly in the console.
 *   <li><b>Warnings</b> skip one block and carry on. The case that matters here is an
 *       override naming an arena or kit that has since been deleted: that is a
 *       leftover, not a mistake about money, and deleting one arena must never be
 *       able to switch off the whole economy of a server.
 * </ul>
 */
public final class RewardTableLoader
{
    /** Turns an arena or kit token from rewards.yml into its id, or {@code null} if no such resource exists. */
    public interface ResourceResolver
    {
        Integer resolve(String token);
    }

    /**
     * The outcome of a load. A table is always present: on error it is
     * {@link RewardTable#disabled()}, so callers never have to null-check it.
     */
    public record Result(RewardTable table, List<String> errors, List<String> warnings)
    {
        public Result
        {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean isValid()
        {
            return errors.isEmpty();
        }
    }

    private static final Set<String> ROOT_KEYS =
            Set.of("enabled", "pay-on-disconnect", "defaults", "overrides", "multipliers");
    private static final Set<String> LAYER_KEYS = Set.of("money", "experience", "items", "commands");
    private static final Set<String> OVERRIDE_KEYS = Set.of("arenas", "kits", "combinations");
    private static final Set<String> MULTIPLIER_KEYS = Set.of("permission", "factor");

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final ResourceResolver arenas;
    private final ResourceResolver kits;

    private RewardTableLoader(ResourceResolver arenas, ResourceResolver kits)
    {
        this.arenas = arenas;
        this.kits = kits;
    }

    public static Result load(ConfigurationSection root, ResourceResolver arenas, ResourceResolver kits)
    {
        return new RewardTableLoader(arenas, kits).read(root);
    }

    private Result read(ConfigurationSection root)
    {
        if (root == null)
        {
            errors.add("rewards.yml could not be read at all");
            return new Result(RewardTable.disabled(), errors, warnings);
        }

        rejectUnknownKeys(root, ROOT_KEYS, "");

        boolean enabled = readBoolean(root, "enabled", true);
        PayOnDisconnect payOnDisconnect = readPayOnDisconnect(root);

        Map<RewardOutcome, RewardLayer> defaults =
                readOutcomes(childSection(root, "defaults"), "defaults", outcomeKeys());

        ConfigurationSection overrides = childSection(root, "overrides");

        if (overrides != null)
            rejectUnknownKeys(overrides, OVERRIDE_KEYS, "overrides.");

        Map<Integer, Map<RewardOutcome, RewardLayer>> arenaOverrides =
                readResourceOverrides(childSection(overrides, "arenas"), "overrides.arenas", arenas, "arena");
        Map<Integer, Map<RewardOutcome, RewardLayer>> kitOverrides =
                readResourceOverrides(childSection(overrides, "kits"), "overrides.kits", kits, "kit");
        Map<RewardTable.Combination, Map<RewardOutcome, RewardLayer>> combinationOverrides =
                readCombinations(overrides);

        List<RewardMultiplier> multipliers = readMultipliers(root);

        // Building the table only when the file is clean is what makes the error
        // severity real: a rejected file cannot half-apply.
        RewardTable table = errors.isEmpty()
                ? new RewardTable(enabled, payOnDisconnect, defaults, arenaOverrides, kitOverrides,
                        combinationOverrides, multipliers)
                : RewardTable.disabled();

        return new Result(table, errors, warnings);
    }

    // --- structure ---------------------------------------------------------

    private Map<RewardOutcome, RewardLayer> readOutcomes(ConfigurationSection section, String path,
                                                         Set<String> allowedKeys)
    {
        Map<RewardOutcome, RewardLayer> outcomes = new EnumMap<>(RewardOutcome.class);

        if (section == null)
            return outcomes;

        rejectUnknownKeys(section, allowedKeys, path + ".");

        for (RewardOutcome outcome : RewardOutcome.values())
        {
            String key = outcome.configKey();

            if (!section.contains(key))
                continue;

            ConfigurationSection layer = section.getConfigurationSection(key);

            if (layer == null)
            {
                errors.add(path + "." + key + " must be a block of reward fields, not a single value");
                continue;
            }

            outcomes.put(outcome, readLayer(layer, path + "." + key));
        }

        return outcomes;
    }

    private RewardLayer readLayer(ConfigurationSection section, String path)
    {
        rejectUnknownKeys(section, LAYER_KEYS, path + ".");

        Double money = section.contains("money")
                ? readNonNegativeDouble(section, "money", path + ".money") : null;
        Integer experience = section.contains("experience")
                ? readNonNegativeInt(section, "experience", path + ".experience") : null;
        List<ItemStack> items = section.contains("items") ? readItems(section, path + ".items") : null;
        List<String> commands = section.contains("commands") ? readCommands(section, path + ".commands") : null;

        return new RewardLayer(money, experience, items, commands);
    }

    private Map<Integer, Map<RewardOutcome, RewardLayer>> readResourceOverrides(
            ConfigurationSection section, String path, ResourceResolver resolver, String kind)
    {
        Map<Integer, Map<RewardOutcome, RewardLayer>> overrides = new LinkedHashMap<>();

        if (section == null)
            return overrides;

        for (String key : section.getKeys(false))
        {
            Integer id = resolver.resolve(key);

            if (id == null)
            {
                warnings.add(path + "." + key + " names a " + kind + " that does not exist; that block is ignored");
                continue;
            }

            ConfigurationSection outcomes = section.getConfigurationSection(key);

            if (outcomes == null)
            {
                errors.add(path + "." + key + " must be a block of win and loss rewards");
                continue;
            }

            // Reachable because a block may be keyed by id and another by name, both
            // pointing at the same arena. Which one wins would be arbitrary, so neither does.
            if (overrides.containsKey(id))
            {
                errors.add(path + "." + key + " configures " + kind + " " + id + " a second time; "
                        + "remove one of the blocks so it is clear which applies");
                continue;
            }

            overrides.put(id, readOutcomes(outcomes, path + "." + key, outcomeKeys()));
        }

        return overrides;
    }

    private Map<RewardTable.Combination, Map<RewardOutcome, RewardLayer>> readCombinations(
            ConfigurationSection overrides)
    {
        Map<RewardTable.Combination, Map<RewardOutcome, RewardLayer>> combinations = new LinkedHashMap<>();

        if (overrides == null || !overrides.contains("combinations"))
            return combinations;

        if (!overrides.isList("combinations"))
        {
            errors.add("overrides.combinations must be a list of blocks, each naming an arena and a kit");
            return combinations;
        }

        Set<String> allowedKeys = new LinkedHashSet<>(outcomeKeys());
        allowedKeys.add("arena");
        allowedKeys.add("kit");

        List<Map<?, ?>> entries = overrides.getMapList("combinations");

        for (int index = 0; index < entries.size(); index++)
        {
            String path = "overrides.combinations[" + index + "]";
            ConfigurationSection entry = asSection(entries.get(index));

            Integer arenaId = readReference(entry, "arena", path, arenas, "arena");
            Integer kitId = readReference(entry, "kit", path, kits, "kit");

            // Read the rewards even for a block that is about to be skipped, so a typo
            // inside it is still reported rather than hidden behind the warning.
            Map<RewardOutcome, RewardLayer> outcomes = readOutcomes(entry, path, allowedKeys);

            if (arenaId == null || kitId == null)
                continue;

            RewardTable.Combination combination = new RewardTable.Combination(arenaId, kitId);

            if (combinations.containsKey(combination))
            {
                errors.add(path + " repeats the combination of arena " + arenaId + " and kit " + kitId
                        + "; remove one of the blocks so it is clear which applies");
                continue;
            }

            combinations.put(combination, outcomes);
        }

        return combinations;
    }

    private Integer readReference(ConfigurationSection entry, String key, String path,
                                  ResourceResolver resolver, String kind)
    {
        Object raw = entry.get(key);

        if (raw == null)
        {
            errors.add(path + " is missing " + quote(key));
            return null;
        }

        Integer id = resolver.resolve(String.valueOf(raw));

        if (id == null)
            warnings.add(path + " names " + kind + " " + quote(String.valueOf(raw))
                    + ", which does not exist; that block is ignored");

        return id;
    }

    private List<RewardMultiplier> readMultipliers(ConfigurationSection root)
    {
        List<RewardMultiplier> multipliers = new ArrayList<>();

        if (!root.contains("multipliers"))
            return multipliers;

        if (!root.isList("multipliers"))
        {
            errors.add("multipliers must be a list of blocks, each with a permission and a factor");
            return multipliers;
        }

        Set<String> seen = new HashSet<>();
        List<Map<?, ?>> entries = root.getMapList("multipliers");

        for (int index = 0; index < entries.size(); index++)
        {
            String path = "multipliers[" + index + "]";
            ConfigurationSection entry = asSection(entries.get(index));
            rejectUnknownKeys(entry, MULTIPLIER_KEYS, path + ".");

            String permission = entry.getString("permission");

            if (permission == null || permission.isBlank())
            {
                errors.add(path + ".permission must be a permission node");
                continue;
            }

            Double factor = readNonNegativeDouble(entry, "factor", path + ".factor");

            if (factor == null)
                continue;

            if (!seen.add(permission.toLowerCase(Locale.ROOT)))
            {
                errors.add(path + " repeats the permission " + quote(permission)
                        + "; remove one of the blocks so it is clear which factor applies");
                continue;
            }

            multipliers.add(new RewardMultiplier(permission, factor));
        }

        return multipliers;
    }

    // --- values ------------------------------------------------------------

    private List<ItemStack> readItems(ConfigurationSection section, String path)
    {
        if (!section.isList("items"))
        {
            errors.add(path + " must be a list of item definitions");
            return null;
        }

        List<?> raw = section.getList("items");
        List<Map<?, ?>> definitions = section.getMapList("items");

        // getMapList silently drops anything that is not a map, so a bare "- DIAMOND"
        // would otherwise vanish without the admin ever hearing about it.
        if (raw != null && raw.size() != definitions.size())
            errors.add(path + " must contain only item definitions, each starting with a material");

        List<ItemStack> items = new ArrayList<>();

        for (int index = 0; index < definitions.size(); index++)
        {
            try
            {
                items.add(ItemStackParser.parse(asSection(definitions.get(index))));
            }
            catch (RuntimeException exception)
            {
                errors.add(path + "[" + index + "] is not a valid item: " + exception.getMessage());
            }
        }

        return items;
    }

    /**
     * Commands are stored without a leading slash because that is what
     * {@code Bukkit.dispatchCommand} expects, but admins habitually write one, so
     * accepting both and normalising here is kinder than rejecting the file.
     */
    private List<String> readCommands(ConfigurationSection section, String path)
    {
        if (!section.isList("commands"))
        {
            errors.add(path + " must be a list of commands");
            return null;
        }

        List<String> commands = new ArrayList<>();

        for (String command : section.getStringList("commands"))
        {
            String trimmed = command == null ? "" : command.trim();

            if (trimmed.startsWith("/"))
                trimmed = trimmed.substring(1).trim();

            if (trimmed.isEmpty())
            {
                errors.add(path + " contains an empty command");
                continue;
            }

            commands.add(trimmed);
        }

        return commands;
    }

    private Double readNonNegativeDouble(ConfigurationSection section, String key, String path)
    {
        Object raw = section.get(key);

        if (!(raw instanceof Number number))
        {
            errors.add(path + " must be a number of 0 or greater; got " + quote(String.valueOf(raw)));
            return null;
        }

        if (number.doubleValue() < 0)
        {
            errors.add(path + " must be 0 or greater; Duels pays rewards, it never takes money away");
            return null;
        }

        return number.doubleValue();
    }

    private Integer readNonNegativeInt(ConfigurationSection section, String key, String path)
    {
        Object raw = section.get(key);

        if (!(raw instanceof Number number))
        {
            errors.add(path + " must be a whole number of 0 or greater; got " + quote(String.valueOf(raw)));
            return null;
        }

        if (number.doubleValue() < 0)
        {
            errors.add(path + " must be 0 or greater");
            return null;
        }

        return number.intValue();
    }

    private boolean readBoolean(ConfigurationSection section, String key, boolean fallback)
    {
        if (!section.contains(key))
            return fallback;

        Object raw = section.get(key);

        if (raw instanceof Boolean value)
            return value;

        errors.add(key + " must be true or false; got " + quote(String.valueOf(raw)));
        return fallback;
    }

    private PayOnDisconnect readPayOnDisconnect(ConfigurationSection root)
    {
        if (!root.contains("pay-on-disconnect"))
            return PayOnDisconnect.WINNER_ONLY;

        String raw = root.getString("pay-on-disconnect");
        PayOnDisconnect parsed = PayOnDisconnect.parse(raw);

        if (parsed != null)
            return parsed;

        errors.add("pay-on-disconnect must be one of WINNER_ONLY, BOTH or NEITHER; got "
                + quote(String.valueOf(raw)));
        return PayOnDisconnect.WINNER_ONLY;
    }

    // --- helpers -----------------------------------------------------------

    /**
     * An unknown key is an error rather than something to ignore because the realistic
     * cause is a typo of a real key. Ignoring {@code monney: 500} would pay the default
     * amount forever while the admin believed they had configured 500.
     */
    private void rejectUnknownKeys(ConfigurationSection section, Set<String> allowed, String pathPrefix)
    {
        for (String key : section.getKeys(false))
            if (!allowed.contains(key))
                errors.add("unknown option " + quote(pathPrefix + key) + "; expected one of " + sorted(allowed));
    }

    private static String quote(String value)
    {
        return "'" + value + "'";
    }

    private static String sorted(Set<String> allowed)
    {
        return String.join(", ", new TreeSet<>(allowed));
    }

    private static Set<String> outcomeKeys()
    {
        Set<String> keys = new LinkedHashSet<>();

        for (RewardOutcome outcome : RewardOutcome.values())
            keys.add(outcome.configKey());

        return keys;
    }

    private static ConfigurationSection childSection(ConfigurationSection parent, String key)
    {
        return parent == null ? null : parent.getConfigurationSection(key);
    }

    /**
     * A list entry arrives as a plain {@link Map} rather than a section, so it has to be
     * wrapped before the same reading code can be pointed at it.
     */
    private static ConfigurationSection asSection(Map<?, ?> map)
    {
        return new MemoryConfiguration().createSection("entry", map);
    }
}
