package dev.minegoon.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/** The filled cells of a crafting recipe, one choice per item consumed. */
public final class CraftingGrid {

    private CraftingGrid() {
    }

    /** Empty for recipes the helper does not craft by hand, such as special or transmute recipes. */
    public static List<RecipeChoice> cells(Recipe recipe) {
        List<RecipeChoice> cells = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (char key : row.toCharArray()) {
                    RecipeChoice choice = choices.get(key);
                    if (choice != null) {
                        cells.add(choice);
                    }
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            cells.addAll(shapeless.getChoiceList());
        }
        return cells;
    }

    /** Recipes bigger than the 2x2 inventory grid need a crafting table. */
    public static boolean needsTable(Recipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            return shape.length > 2 || shape[0].length() > 2;
        }
        return cells(recipe).size() > 4;
    }
}
