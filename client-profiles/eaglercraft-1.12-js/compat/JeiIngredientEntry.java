import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.recipe.IIngredientType;
import mezz.jei.gui.ingredients.IIngredientListElement;
import mezz.jei.ingredients.IngredientListElementFactory;
import mezz.jei.ingredients.IngredientBlacklistInternal;
import mezz.jei.ingredients.IngredientFilter;
import mezz.jei.suffixtree.GeneralizedSuffixTree;
import mezz.jei.startup.IModIdHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.util.ITooltipFlag;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSBody;


public final class JeiIngredientEntry {
  private static final IIngredientType<ClientItem> ITEM_TYPE = () -> ClientItem.class;
  private static final ClientItemHelper ITEM_HELPER = new ClientItemHelper();
  private static final ClientItemRenderer ITEM_RENDERER = new ClientItemRenderer();
  private static final ClientRegistry ITEM_REGISTRY = new ClientRegistry();
  private static final ClientModIdHelper MOD_ID_HELPER = new ClientModIdHelper();
  private static GeneralizedSuffixTree searchTree = new GeneralizedSuffixTree();
  private static IngredientFilter ingredientFilter;

  private JeiIngredientEntry() {
  }

  public static void main(String[] args) {
    searchTree = new GeneralizedSuffixTree();
  }

  
  @JSExport
  public static void goldClientJeiSetLocale(String languageCode) {
    Translator.setLocale(languageCode);
  }

  @JSExport
  public static String goldClientJeiInitialize(String[] ids, String[] displayNames) {
    return initialize(ids, displayNames, null, null);
  }

  @JSExport
  public static String goldClientJeiInitializeWithTooltips(
      String[] ids,
      String[] displayNames,
      String[] normalTooltipData,
      String[] advancedTooltipData) {
    if (normalTooltipData == null || advancedTooltipData == null
        || ids == null || displayNames == null
        || normalTooltipData.length != ids.length
        || advancedTooltipData.length != ids.length) {
      throw new IllegalArgumentException("Tooltip data must match the client item count");
    }
    return initialize(ids, displayNames, normalTooltipData, advancedTooltipData);
  }

  private static String initialize(
      String[] ids,
      String[] displayNames,
      String[] normalTooltipData,
      String[] advancedTooltipData) {
    if (ids == null || displayNames == null || ids.length != displayNames.length) {
      throw new IllegalArgumentException("Client item IDs and display names must have equal lengths");
    }
    Set<String> uniqueIds = new HashSet<>();
    List<ClientItem> input = new ArrayList<>(ids.length);
    for (int index = 0; index < ids.length; index++) {
      String id = ids[index];
      String displayName = displayNames[index];
      if (id == null || id.isBlank() || displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("Client item ID and display name must be non-empty");
      }
      if (!uniqueIds.add(id)) {
        throw new IllegalArgumentException("Duplicate client item identifier: " + id);
      }
      boolean tooltipsCaptured = normalTooltipData != null;
      List<String> normalTooltips = tooltipsCaptured
          ? decodeTooltipData(normalTooltipData[index])
          : List.of();
      List<String> advancedTooltips = tooltipsCaptured
          ? decodeTooltipData(advancedTooltipData[index])
          : List.of();
      input.add(new ClientItem(
          id, displayName, normalTooltips, advancedTooltips, tooltipsCaptured));
    }

    ITEM_REGISTRY.setItems(input);
    var originalElements = IngredientListElementFactory.createBaseList(
        ITEM_REGISTRY, MOD_ID_HELPER);
    ingredientFilter = null;
    if (normalTooltipData != null) {
      ingredientFilter = new IngredientFilter(new IngredientBlacklistInternal());
      ingredientFilter.addIngredients(originalElements);
      ingredientFilter.modesChanged();
    }
    GeneralizedSuffixTree nextTree = new GeneralizedSuffixTree();
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < originalElements.size(); index++) {
      IIngredientListElement<?> element = originalElements.get(index);
      if (element == null) {
        throw new IllegalStateException("JEI returned a null ingredient list element at " + index);
      }
      Object ingredient = element.getIngredient();
      if (!(ingredient instanceof ClientItem item)) {
        throw new IllegalStateException(
            "JEI returned an ingredient outside the registered client item type at " + index);
      }
      String id = ITEM_HELPER.getUniqueId(item);
      String displayName = element.getDisplayName();
      nextTree.put(id + " " + displayName, index);
      if (index != 0) {
        result.append(',');
      }
      result.append("{\"id\":\"").append(jsonEscape(id))
          .append("\",\"displayName\":\"").append(jsonEscape(displayName)).append("\"}");
    }
    result.append(']');
    nextTree.trimToSize();
    searchTree = nextTree;
    return result.toString();
  }

  @JSExport
  public static String goldClientJeiSearch(String query) {
    IntSet matches = searchTree.search(query);
    int[] indices = matches.toIntArray();
    Arrays.sort(indices);
    return Arrays.toString(indices);
  }

  @JSExport
  public static String goldClientJeiFilter(String query) {
    if (ingredientFilter == null) {
      throw new IllegalStateException("Original JEI IngredientFilter is not initialized");
    }
    if (query == null) {
      throw new IllegalArgumentException("JEI filter text must not be null");
    }
    ingredientFilter.setFilterText(query);
    List<IIngredientListElement> matches = ingredientFilter.getIngredientList();
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < matches.size(); index++) {
      IIngredientListElement<?> element = matches.get(index);
      Object value = element.getIngredient();
      if (!(value instanceof ClientItem item)) {
        throw new IllegalStateException(
            "Original JEI filter returned an unsupported client ingredient");
      }
      if (index != 0) {
        result.append(',');
      }
      result.append("{\"id\":\"").append(jsonEscape(item.id))
          .append("\",\"displayName\":\"")
          .append(jsonEscape(element.getDisplayName())).append("\"}");
    }
    return result.append(']').toString();
  }

  @JSExport
  public static String goldClientJeiTooltip(String id, boolean advanced) {
    ClientItem ingredient = ITEM_REGISTRY.getItem(id);
    if (ingredient == null) {
      throw new IllegalArgumentException("Unknown client item identifier: " + id);
    }
    ITooltipFlag flag = advanced
        ? ITooltipFlag.TooltipFlags.ADVANCED
        : ITooltipFlag.TooltipFlags.NORMAL;
    List<String> lines = ITEM_RENDERER.getTooltip(null, ingredient, flag);
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < lines.size(); index++) {
      if (index != 0) {
        result.append(',');
      }
      result.append('"').append(jsonEscape(lines.get(index))).append('"');
    }
    return result.append(']').toString();
  }

  private static String jsonEscape(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"':
          escaped.append("\\\"");
          break;
        case '\\':
          escaped.append("\\\\");
          break;
        case '\b':
          escaped.append("\\b");
          break;
        case '\f':
          escaped.append("\\f");
          break;
        case '\n':
          escaped.append("\\n");
          break;
        case '\r':
          escaped.append("\\r");
          break;
        case '\t':
          escaped.append("\\t");
          break;
        default:
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
      }
    }
    return escaped.toString();
  }

  private static String modId(String id) {
    int separator = id.indexOf(':');
    return separator < 0 ? "" : id.substring(0, separator);
  }

  private static UnsupportedOperationException unsupported(String feature) {
    return new UnsupportedOperationException(
        "The supplied Eaglercraft JS profile does not expose " + feature);
  }

  @JSBody(params = {"id", "advanced"}, script = """
      if (typeof window.__goldClientJeiGetTooltipData !== "function") {
          throw new Error("The supplied client tooltip adapter is not installed");
      }
      return window.__goldClientJeiGetTooltipData(id, advanced);
      """)
  private static native String getClientTooltipData(String id, boolean advanced);

  private static List<String> decodeTooltipData(String encoded) {
    List<String> lines = new ArrayList<>();
    int offset = 0;
    while (offset < encoded.length()) {
      int separator = encoded.indexOf(':', offset);
      if (separator < 0 || separator == offset) {
        throw new IllegalStateException("Malformed client tooltip data length");
      }
      int lineLength;
      try {
        lineLength = Integer.parseInt(encoded.substring(offset, separator));
      } catch (NumberFormatException exception) {
        throw new IllegalStateException("Malformed client tooltip data length", exception);
      }
      int lineStart = separator + 1;
      if (lineLength < 0 || lineLength > encoded.length() - lineStart) {
        throw new IllegalStateException("Client tooltip data length is out of bounds");
      }
      lines.add(encoded.substring(lineStart, lineStart + lineLength));
      offset = lineStart + lineLength;
    }
    return List.copyOf(lines);
  }

  private static final class ClientItem {
    private final String id;
    private final String displayName;
    private final List<String> normalTooltips;
    private final List<String> advancedTooltips;
    private final boolean tooltipsCaptured;

    private ClientItem(
        String id,
        String displayName,
        List<String> normalTooltips,
        List<String> advancedTooltips,
        boolean tooltipsCaptured) {
      this.id = id;
      this.displayName = displayName;
      this.normalTooltips = List.copyOf(normalTooltips);
      this.advancedTooltips = List.copyOf(advancedTooltips);
      this.tooltipsCaptured = tooltipsCaptured;
    }
  }

  private static final class ClientItemHelper implements IIngredientHelper<ClientItem> {
    @Override
    public ClientItem getMatch(Iterable<ClientItem> ingredients, ClientItem ingredient) {
      for (ClientItem candidate : ingredients) {
        if (candidate.id.equals(ingredient.id)) {
          return candidate;
        }
      }
      return null;
    }

    @Override
    public String getDisplayName(ClientItem ingredient) {
      return ingredient.displayName;
    }

    @Override
    public String getUniqueId(ClientItem ingredient) {
      return ingredient.id;
    }

    @Override
    public String getWildcardId(ClientItem ingredient) {
      return ingredient.id;
    }

    @Override
    public String getModId(ClientItem ingredient) {
      return modId(ingredient.id);
    }

    @Override
    public String getResourceId(ClientItem ingredient) {
      return ingredient.id;
    }

    @Override
    public ClientItem copyIngredient(ClientItem ingredient) {
      return new ClientItem(
          ingredient.id,
          ingredient.displayName,
          ingredient.normalTooltips,
          ingredient.advancedTooltips,
          ingredient.tooltipsCaptured);
    }

    @Override
    public String getErrorInfo(ClientItem ingredient) {
      return ingredient.id;
    }

    @Override
    public Iterable<java.awt.Color> getColors(ClientItem ingredient) {
      throw unsupported("JEI ingredient colors");
    }

    @Override
    public Collection<String> getOreDictNames(ClientItem ingredient) {
      throw unsupported("the Forge OreDictionary");
    }

    @Override
    public Collection<String> getCreativeTabNames(ClientItem ingredient) {
      throw unsupported("creative-tab ingredient metadata");
    }
  }

  private static final class ClientItemRenderer implements IIngredientRenderer<ClientItem> {
    @Override
    public void render(Minecraft minecraft, int xPosition, int yPosition, ClientItem ingredient) {
      throw unsupported("JEI item rendering");
    }

    @Override
    public List<String> getTooltip(
        Minecraft minecraft, ClientItem ingredient, ITooltipFlag tooltipFlag) {
      if (tooltipFlag == null) {
        throw new IllegalArgumentException("Minecraft tooltip flags must not be null");
      }
      if (ingredient.tooltipsCaptured) {
        return tooltipFlag.func_194127_a()
            ? ingredient.advancedTooltips
            : ingredient.normalTooltips;
      }
      return decodeTooltipData(
          getClientTooltipData(ingredient.id, tooltipFlag.func_194127_a()));
    }

    @Override
    public FontRenderer getFontRenderer(Minecraft minecraft, ClientItem ingredient) {
      throw unsupported("JEI item font rendering");
    }
  }

  @SuppressWarnings("unchecked")
  private static final class ClientRegistry implements IIngredientRegistry {
    private List<ClientItem> items = List.of();

    private void setItems(List<ClientItem> items) {
      this.items = List.copyOf(items);
    }

    private ClientItem getItem(String id) {
      for (ClientItem item : items) {
        if (item.id.equals(id)) {
          return item;
        }
      }
      return null;
    }

    @Override
    public <V> Collection<V> getAllIngredients(IIngredientType<V> type) {
      requireItemType(type);
      return (Collection<V>) items;
    }

    @Override
    public <V> IIngredientHelper<V> getIngredientHelper(V ingredient) {
      if (ingredient instanceof ClientItem) {
        return (IIngredientHelper<V>) ITEM_HELPER;
      }
      throw unsupported("ingredient helper for " + ingredient.getClass().getName());
    }

    @Override
    public <V> IIngredientHelper<V> getIngredientHelper(IIngredientType<V> type) {
      requireItemType(type);
      return (IIngredientHelper<V>) ITEM_HELPER;
    }

    @Override
    public <V> IIngredientRenderer<V> getIngredientRenderer(V ingredient) {
      if (ingredient instanceof ClientItem) {
        return (IIngredientRenderer<V>) ITEM_RENDERER;
      }
      throw unsupported("ingredient renderer for " + ingredient.getClass().getName());
    }

    @Override
    public <V> IIngredientRenderer<V> getIngredientRenderer(IIngredientType<V> type) {
      requireItemType(type);
      return (IIngredientRenderer<V>) ITEM_RENDERER;
    }

    @Override
    public Collection<IIngredientType> getRegisteredIngredientTypes() {
      return List.of(ITEM_TYPE);
    }

    @Override
    public <V> IIngredientType<V> getIngredientType(V ingredient) {
      if (ingredient instanceof ClientItem) {
        return (IIngredientType<V>) ITEM_TYPE;
      }
      throw unsupported("ingredient type for " + ingredient.getClass().getName());
    }

    @Override
    public <V> IIngredientType<V> getIngredientType(Class<? extends V> ingredientClass) {
      if (ingredientClass == ClientItem.class) {
        return (IIngredientType<V>) ITEM_TYPE;
      }
      throw unsupported("ingredient class " + ingredientClass.getName());
    }

    @Override
    public <V> Collection<V> getAllIngredients(Class<V> ingredientClass) {
      if (ingredientClass == ClientItem.class) {
        return (Collection<V>) items;
      }
      throw unsupported("ingredient class " + ingredientClass.getName());
    }

    @Override
    public <V> IIngredientHelper<V> getIngredientHelper(Class<? extends V> ingredientClass) {
      if (ingredientClass == ClientItem.class) {
        return (IIngredientHelper<V>) ITEM_HELPER;
      }
      throw unsupported("ingredient class " + ingredientClass.getName());
    }

    @Override
    public <V> IIngredientRenderer<V> getIngredientRenderer(
        Class<? extends V> ingredientClass) {
      if (ingredientClass == ClientItem.class) {
        return (IIngredientRenderer<V>) ITEM_RENDERER;
      }
      throw unsupported("ingredient class " + ingredientClass.getName());
    }

    @Override
    public Collection<Class> getRegisteredIngredientClasses() {
      return List.of(ClientItem.class);
    }

    private void requireItemType(IIngredientType<?> type) {
      if (type != ITEM_TYPE) {
        throw unsupported("ingredient type " + type.getIngredientClass().getName());
      }
    }

    @Override
    public List<net.minecraft.item.ItemStack> getFuels() {
      throw unsupported("Forge fuel registration");
    }

    @Override
    public List<net.minecraft.item.ItemStack> getPotionIngredients() {
      throw unsupported("Forge potion ingredient registration");
    }

    @Override
    public <V> void addIngredientsAtRuntime(
        IIngredientType<V> type, Collection<V> ingredients) {
      throw unsupported("runtime JEI ingredient registration");
    }

    @Override
    public <V> void removeIngredientsAtRuntime(
        IIngredientType<V> type, Collection<V> ingredients) {
      throw unsupported("runtime JEI ingredient removal");
    }

    @Override
    public <V> void addIngredientsAtRuntime(Class<V> ingredientClass, Collection<V> ingredients) {
      throw unsupported("runtime JEI ingredient registration");
    }

    @Override
    public <V> void removeIngredientsAtRuntime(
        Class<V> ingredientClass, Collection<V> ingredients) {
      throw unsupported("runtime JEI ingredient removal");
    }

    @Override
    public <V> void addIngredientsAtRuntime(Class<V> ingredientClass, List<V> ingredients) {
      throw unsupported("runtime JEI ingredient registration");
    }

    @Override
    public <V> void removeIngredientsAtRuntime(Class<V> ingredientClass, List<V> ingredients) {
      throw unsupported("runtime JEI ingredient removal");
    }

    @Override
    public <V> List<V> getIngredients(Class<V> ingredientClass) {
      if (ingredientClass == ClientItem.class) {
        return (List<V>) items;
      }
      throw unsupported("ingredient class " + ingredientClass.getName());
    }
  }

  private static final class ClientModIdHelper implements IModIdHelper {
    @Override
    public String getModNameForModId(String modId) {
      return modId;
    }

    @Override
    public String getFormattedModNameForModId(String modId) {
      return modId;
    }

    @Override
    public <T> String getModNameForIngredient(T ingredient, IIngredientHelper<T> helper) {
      return helper.getModId(ingredient);
    }

    @Override
    public <T> List<String> addModNameToIngredientTooltip(
        List<String> tooltip, T ingredient, IIngredientHelper<T> helper) {
      throw unsupported("localized mod-name tooltips");
    }

    @Override
    public String getModNameTooltipFormatting() {
      throw unsupported("localized mod-name tooltip formatting");
    }
  }
}
