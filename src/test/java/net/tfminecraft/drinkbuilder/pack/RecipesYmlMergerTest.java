package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.google.gson.Gson;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.PendingDrink;

class RecipesYmlMergerTest {
	@TempDir Path directory;
	private String originalFolder;
	private List<Cache.Ingredient> originalIngredients;

	@BeforeEach
	void configureIngredients() {
		originalFolder = Cache.breweryxFolder;
		originalIngredients = Cache.ingredients;
		Cache.breweryxFolder = directory.resolve("BreweryX").toString();
		Cache.ingredients = List.of(
			new Cache.Ingredient("apple", "material", "APPLE", "Apple", null),
			new Cache.Ingredient("wheat", "material", "WHEAT", "Wheat", null),
			new Cache.Ingredient("broken", "material", " ", null, null));
	}

	@AfterEach
	void restoreCache() {
		Cache.breweryxFolder = originalFolder;
		Cache.ingredients = originalIngredients;
	}

	@Test
	void mapsNamesAndNumbersToBreweryCodes() throws IOException {
		String[] names = {"any", "birch", "oak", "jungle", "spruce", "acacia", "dark oak",
			"crimson", "warped", "mangrove", "cherry", "bamboo", "cut-copper", "Pale Oak"};
		for (int i = 0; i < names.length; i++) {
			assertEquals(i, RecipesYmlMerger.woodCode(names[i]));
			assertEquals(i, RecipesYmlMerger.woodCode(i));
			assertEquals(i, RecipesYmlMerger.woodCode(i + ".00"));
			assertEquals(i, RecipesYmlMerger.woodCode(String.valueOf(i)));
		}
		assertEquals(4, RecipesYmlMerger.woodCode(4.0d));
		assertEquals(4, RecipesYmlMerger.woodCode(4.0f));
		assertEquals(4, RecipesYmlMerger.woodCode(new BigDecimal("4.000")));
		assertNull(RecipesYmlMerger.woodCode(null));
		assertNull(RecipesYmlMerger.woodCode(""));
		assertNull(RecipesYmlMerger.woodCode("   "));
	}

	@Test
	void rejectsInvalidWoodIncludingNonFiniteFractionalAndOverflowValues() {
		for (Object wood : List.of("mahogany", 14, -1, 1.5d, Double.NaN,
			Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Long.MAX_VALUE,
			"99999999999999999999", "99999999999999999999.00", "13.0000000000000001",
			new BigDecimal("13.0000000000000001"), new BigDecimal("99999999999999999"))) {
			assertThrows(IOException.class, () -> RecipesYmlMerger.woodCode(wood), wood.toString());
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void preservesWebsiteJsonQuantitiesInVanillaAndCustomBreweryTokens() throws IOException {
		Cache.ingredients = List.of(
			new Cache.Ingredient("apple", "vanilla", "APPLE", "Apple", null),
			new Cache.Ingredient("grape", "itemsadder", "itemsadder:tfmc_cooking:grape", "Grape", null),
			new Cache.Ingredient("bark", "mmoitems", "MMOItems:BARK", "Bark", null));
		Map<String, Object> recipe = new Gson().fromJson("""
			{"ingredients":[{"id":"apple","amount":3},
			{"id":"grape","amount":5},{"id":"bark","amount":2}],
			"cooking_time":12,"distill_runs":2,"wood":4,"age":5,
			"difficulty":4,"alcohol":9}
			""", Map.class);
		RecipesYmlMerger.merge(null, drink("submission", "Name", recipe), 20001, null);
		ConfigurationSection section = load().getConfigurationSection("recipes.submission");
		assertEquals(List.of("APPLE/3", "itemsadder:tfmc_cooking:grape/5", "MMOItems:BARK/2"),
			section.getStringList("ingredients"));
		assertEquals(12, section.getInt("cookingtime"));
		assertEquals(4, section.getInt("wood"));
	}

	@Test
	void translatesConfusionFromWebsiteObjectsAndLegacyStrings() throws IOException {
		Map<String, Object> recipe = baseRecipe();
		recipe.put("effects", List.of(Map.of("type", "confusion", "level", "1-2", "duration", "30-60"), "confusion/1/20"));
		RecipesYmlMerger.merge(null, drink("effects", "Name", recipe), 20001, null);
		assertEquals(List.of("NAUSEA/1-2/30-60", "NAUSEA/1/20"), load().getStringList("recipes.effects.effects"));

		// Gson parses website JSON numbers as doubles.
		recipe.put("effects", List.of(Map.of("type", "blindness", "level", 45.0, "duration", 10.0),
			Map.of("type", "haste", "duration", 40.0)));
		RecipesYmlMerger.merge(null, drink("effects", "Name", recipe), 20001, null);
		assertEquals(List.of("BLINDNESS/45/10", "HASTE/40"), load().getStringList("recipes.effects.effects"));
	}

	@Test
	void rejectsInvalidQuantitiesWithoutOverwritingExistingRecipes() throws IOException {
		Files.createDirectories(recipeFile().getParent());
		String original = "recipes:\n  existing:\n    name: Keep\n";
		Files.writeString(recipeFile(), original);
		for (Object amount : Arrays.asList(null, "bad", 0, -1, 1.5d, Double.NaN,
			Double.POSITIVE_INFINITY, Long.MAX_VALUE, new BigDecimal("3.0000000000000001"))) {
			Map<String, Object> row = new HashMap<>();
			row.put("id", "apple");
			row.put("amount", amount);
			Map<String, Object> recipe = Map.of("ingredients", List.of(row));
			assertTrue(assertThrows(IOException.class, () -> RecipesYmlMerger.merge(null,
				drink("submission", "Name", recipe), 20001, null)).getMessage().contains("positive integer"));
			assertEquals(original, Files.readString(recipeFile()));
		}
	}

	@Test
	void coloursHandleMissingInvalidAndSingleStops() {
		assertEquals("", RecipesYmlMerger.bakeColourStops(null, List.of("abcdef")));
		assertEquals("", RecipesYmlMerger.bakeColourStops("", List.of("abcdef")));
		assertEquals("hello", RecipesYmlMerger.bakeColourStops("hello", null));
		assertEquals("hello", RecipesYmlMerger.bakeColourStops("hello", List.of()));
		assertEquals("hello", RecipesYmlMerger.bakeColourStops("hello",
			Arrays.asList(null, "#fff", "gggggg", "//////", ":00000", "G00000", "`00000", "@00000")));
		assertEquals("&#abcdefa&#abcdefb", RecipesYmlMerger.bakeColourStops("ab", List.of(" #AbCdEf ")));
		assertEquals("&#019af0x", RecipesYmlMerger.bakeColourStops("x", List.of("invalid", "019af0")));
	}

	@Test
	void gradientsInterpolateChannelsAcrossMultipleStopsAndSingleCharacters() {
		assertEquals("&#000000a&#808080b&#ffffffc",
			RecipesYmlMerger.bakeColourStops("abc", List.of("000000", "ffffff")));
		assertEquals("&#ff0000a&#808000b&#00ff00c&#008080d&#0000ffe",
			RecipesYmlMerger.bakeColourStops("abcde", List.of("ff0000", "00ff00", "0000ff")));
		assertEquals("&#ff0000x", RecipesYmlMerger.bakeColourStops("x", List.of("ff0000", "0000ff")));
	}

	@Test
	void mergesFullRecipeAndPreservesUnrelatedRecipesAndSettings() throws IOException {
		Files.createDirectories(recipeFile().getParent());
		Files.writeString(recipeFile(), "setting: untouched\nrecipes:\n  other:\n    name: other\n  submission:\n    stale: true\n");
		Map<String, Object> recipe = baseRecipe();
		recipe.put("names", " Bad /Normal/Good ");
		recipe.put("name_colours", Arrays.asList(null, " ", "#00FF00"));
		recipe.put("name_bad_colours", List.of("ff0000"));
		recipe.put("name_good_colours", List.of("0000ff"));
		recipe.put("ingredients", List.of("ignored", Map.of(), Map.of("id", " "),
			Map.of("id", " APPLE ", "amount", 3), Map.of("id", "wheat", "amount", "5")));
		recipe.put("cooking_time", "12.9");
		recipe.put("distill_runs", 2);
		recipe.put("distill_time", "invalid");
		recipe.put("wood", "oak");
		recipe.put("age", 5);
		recipe.put("difficulty", "4");
		recipe.put("alcohol", 9);
		recipe.put("lore", Arrays.asList(Map.of("text", "Lore", "colours", List.of("abcdef")),
			Map.of(), Map.of("text", " "), Map.of("text", "\u2003"), null, " ", " plain ", 42,
			Map.of("text", "#25368e Earthy &#abcdefwood"), "Mid #ABCDEF line"));
		recipe.put("drink_message", "Hi");
		recipe.put("drink_message_colours", List.of("123456"));
		recipe.put("drink_title", "Title");
		recipe.put("drink_title_colours", List.of("bad"));
		recipe.put("glint", true);
		recipe.put("effects", Arrays.asList(" speed/1/20 ", " ", null, 42, Map.of(),
			Map.of("type", " "), Map.of("type", "\u2003"), Map.of("type", "jump", "level", 2, "duration", 30),
			Map.of("name", "haste", "duration", 40), Map.of("type", "strength", "level", 1),
			Map.of("type", " ", "name", " regeneration ")));
		recipe.put("server_commands", List.of("op player"));
		recipe.put("player_commands", List.of("unsafe"));
		Logger logger = mock(Logger.class);
		RecipesYmlMerger.merge(null, drink(" submission ", " Display ", recipe), 20001, logger);
		YamlConfiguration yaml = load();
		ConfigurationSection section = yaml.getConfigurationSection("recipes.submission");
		assertNotNull(section);
		assertEquals("untouched", yaml.getString("setting"));
		assertEquals("other", yaml.getString("recipes.other.name"));
		assertFalse(section.contains("stale"));
		assertEquals(RecipesYmlMerger.bakeColourStops("Bad", List.of("ff0000")) + "/"
			+ RecipesYmlMerger.bakeColourStops("Normal", List.of("00ff00")) + "/"
			+ RecipesYmlMerger.bakeColourStops("Good", List.of("0000ff")), section.getString("name"));
		assertTrue(section.getBoolean("enabled"));
		assertEquals(List.of("APPLE/3", "WHEAT/5"), section.getStringList("ingredients"));
		assertEquals(12, section.getInt("cookingtime"));
		assertEquals(2, section.getInt("distillruns"));
		assertEquals(0, section.getInt("distilltime"));
		assertEquals(2, section.getInt("wood"));
		assertEquals(5, section.getInt("age"));
		assertEquals(4, section.getInt("difficulty"));
		assertEquals(9, section.getInt("alcohol"));
		assertEquals(List.of("&#abcdefL&#abcdefo&#abcdefr&#abcdefe", "plain", "42",
			"&#25368e Earthy &#abcdefwood", "Mid &#ABCDEF line"), section.getStringList("lore"));
		assertEquals("&#123456H&#123456i", section.getString("drinkmessage"));
		assertEquals("Title", section.getString("drinktitle"));
		assertTrue(section.getBoolean("glint"));
		assertEquals(List.of("SPEED/1/20", "JUMP_BOOST/2/30", "HASTE/40", "STRENGTH/1", "REGENERATION"), section.getStringList("effects"));
		assertFalse(section.contains("server_commands"));
		assertFalse(section.contains("player_commands"));
		assertFalse(section.contains("color"));
		assertEquals(20001, section.getInt("customModelData"));
		verify(logger).info("[brewery] merged recipe key=submission cmd=20001");
		assertFalse(Files.exists(recipeFile().resolveSibling("recipes.yml.tmp")));
	}

	@Test
	void writesColourOnlyRecipeWithDefaultsAndFallbackNames() throws IOException {
		Logger logger = mock(Logger.class);
		Map<String, Object> recipe = baseRecipe();
		recipe.put("color", " #ABCDEF ");
		RecipesYmlMerger.merge(null, drink("submission", null, recipe), null, logger);
		ConfigurationSection section = load().getConfigurationSection("recipes.submission");
		assertEquals("submission/submission/submission", section.getString("name"));
		assertEquals("ABCDEF", section.getString("color"));
		assertEquals(1, section.getInt("difficulty"));
		assertEquals(0, section.getInt("age"));
		assertEquals(0, section.getInt("cookingtime"));
		assertEquals(0, section.getInt("alcohol"));
		assertEquals(0, section.getInt("distillruns"));
		for (String omitted : List.of("distilltime", "wood", "lore", "effects", "drinkmessage", "drinktitle", "glint", "customModelData")) {
			assertFalse(section.contains(omitted), omitted);
		}
		verify(logger).info("[brewery] merged recipe key=submission color=ABCDEF");
	}

	@Test
	void emptyAndMalformedQualityNamesFallBackAndBooleanFormsAreParsed() throws IOException {
		Object[] glints = {false, "TRUE", "1", "false", "0", "unknown"};
		String[] names = {" ", "bad/normal", "//", "bad//", "/normal/", "//good"};
		String[] expected = {"Display/Display/Display", "Display/Display/Display", "Display/Display/Display",
			"bad/Display/Display", "Display/normal/Display", "Display/Display/good"};
		for (int i = 0; i < glints.length; i++) {
			Map<String, Object> recipe = baseRecipe();
			recipe.put("names", names[i]);
			recipe.put("glint", glints[i]);
			recipe.put("drink_message", " ");
			recipe.put("drink_title", " ");
			recipe.put("color", "aabbcc");
			recipe.put("lore", List.of());
			recipe.put("effects", List.of());
			RecipesYmlMerger.merge(null, drink("submission", " Display ", recipe), null, null);
			ConfigurationSection section = load().getConfigurationSection("recipes.submission");
			assertEquals(expected[i], section.getString("name"));
			assertEquals(i == 1 || i == 2, section.getBoolean("glint"));
			assertEquals("aabbcc", section.getString("color"));
			assertFalse(section.contains("drinkmessage"));
			assertFalse(section.contains("drinktitle"));
		}
	}

	@Test
	void rejectsMissingIdsAndUnmappableIngredientsWithoutChangingExistingFile() throws IOException {
		for (PendingDrink invalid : Arrays.asList(null, drink(null, null, baseRecipe()), drink(" ", null, baseRecipe()))) {
			assertEquals("drink id required", assertThrows(IOException.class,
				() -> RecipesYmlMerger.merge(null, invalid, 1, null)).getMessage());
		}
		Files.createDirectories(recipeFile().getParent());
		String original = "recipes:\n  existing:\n    name: Keep\n";
		Files.writeString(recipeFile(), original);
		for (Object raw : Arrays.asList(null, "not a list", List.of(),
			List.of(Map.of("id", "unknown", "amount", 1)), List.of(Map.of("id", "broken", "amount", 1)))) {
			Map<String, Object> recipe = baseRecipe();
			recipe.put("ingredients", raw);
			assertThrows(IOException.class, () -> RecipesYmlMerger.merge(null, drink("submission", "Name", recipe), 1, null));
			assertEquals(original, Files.readString(recipeFile()));
		}
		for (Object color : Arrays.asList(null, " ")) {
			Map<String, Object> recipe = baseRecipe();
			recipe.put("color", color);
			assertEquals("color-only drink missing recipe.color", assertThrows(IOException.class,
				() -> RecipesYmlMerger.merge(null, drink("submission", "Name", recipe), null, null)).getMessage());
			assertEquals(original, Files.readString(recipeFile()));
		}
	}

	@Test
	void reportsFailureToCreateBreweryDirectory() throws IOException {
		Path blocker = directory.resolve("file");
		Files.writeString(blocker, "not a directory");
		Cache.breweryxFolder = blocker.resolve("child").toString();
		assertTrue(assertThrows(IOException.class, () -> RecipesYmlMerger.merge(null,
			drink("submission", "Name", baseRecipe()), 1, null)).getMessage().startsWith("BreweryX folder missing:"));
	}

	@Test
	void removesOnlyRequestedRecipeAndReportsMissingCases() throws IOException {
		Logger logger = mock(Logger.class);
		for (String id : Arrays.asList(null, " ")) {
			assertEquals("drink id required", assertThrows(IOException.class,
				() -> RecipesYmlMerger.remove(null, id, null)).getMessage());
		}
		assertFalse(RecipesYmlMerger.remove(null, "submission", logger));
		assertFalse(RecipesYmlMerger.remove(null, "submission", null));
		verify(logger).info("[brewery] recipes.yml missing; nothing to remove for submission");
		Files.createDirectories(recipeFile().getParent());
		Files.writeString(recipeFile(), "setting: value\n");
		assertFalse(RecipesYmlMerger.remove(null, "submission", logger));
		Files.writeString(recipeFile(), "recipes:\n  scalar: true\n  other:\n    name: keep\n  submission:\n    name: remove\n");
		assertFalse(RecipesYmlMerger.remove(null, "scalar", null));
		assertFalse(RecipesYmlMerger.remove(null, "missing", logger));
		assertTrue(RecipesYmlMerger.remove(null, " submission ", logger));
		assertNull(load().get("recipes.submission"));
		assertEquals("keep", load().getString("recipes.other.name"));
		assertTrue(load().getBoolean("recipes.scalar"));
		verify(logger).info("[brewery] no recipe key submission");
		verify(logger).info("[brewery] no recipe key missing");
		verify(logger).info("[brewery] removed recipe key=submission");
	}

	@Test
	void fallsBackToNonAtomicReplacementWhenFileSystemDoesNotSupportIt() throws IOException {
		try (MockedStatic<Files> mocked = mockStatic(Files.class, invocation -> {
			if (invocation.getMethod().getName().equals("move")
				&& ((CopyOption[]) invocation.getRawArguments()[2]).length == 2) {
				throw new AtomicMoveNotSupportedException("source", "target", "test filesystem");
			}
			return invocation.callRealMethod();
		})) {
			RecipesYmlMerger.merge(null, drink("submission", "Name", baseRecipe()), 123, null);
			assertEquals(123, load().getInt("recipes.submission.customModelData"));
			assertTrue(RecipesYmlMerger.remove(null, "submission", null));
			assertNull(load().get("recipes.submission"));
		}
	}

	@Test
	void resolvesAbsoluteAndServerRelativePathsAndHandlesMissingAncestors() {
		JavaPlugin plugin = mock(JavaPlugin.class);
		assertEquals(directory.toFile(), RecipesYmlMerger.resolvePath(plugin, directory.toString()));
		verifyNoInteractions(plugin);
		when(plugin.getDataFolder()).thenReturn(directory.resolve("plugins/DrinkBuilder").toFile());
		assertEquals(directory.resolve("plugins/BreweryX").toFile(), RecipesYmlMerger.resolvePath(plugin, "plugins/BreweryX"));
		when(plugin.getDataFolder()).thenReturn(new File("plugin"));
		assertEquals(new File("relative"), RecipesYmlMerger.resolvePath(plugin, "relative"));
		assertEquals(new File(""), RecipesYmlMerger.resolvePath(plugin, null));
		when(plugin.getDataFolder()).thenReturn(new File("plugins/plugin"));
		assertEquals(new File("relative"), RecipesYmlMerger.resolvePath(plugin, "relative"));
	}

	private Map<String, Object> baseRecipe() {
		Map<String, Object> recipe = new HashMap<>();
		recipe.put("ingredients", List.of(Map.of("id", "apple", "amount", 1)));
		return recipe;
	}

	private PendingDrink drink(String id, String name, Map<String, Object> recipe) {
		return new PendingDrink(id, null, null, name, "approved", false, null, recipe, null, null);
	}

	private Path recipeFile() {
		return Path.of(Cache.breweryxFolder, "recipes.yml");
	}

	private YamlConfiguration load() {
		return YamlConfiguration.loadConfiguration(recipeFile().toFile());
	}
}
