package com.example.ooitnpcs;

import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Frog;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Dier-NPC's: welke soorten mogen, en varianten (kleur/soort) instellen. */
final class Animals {

    private Animals() {
    }

    /** Soorten die niet als NPC kunnen (spelers, bazen, dingen die geen wezen zijn). */
    private static final List<EntityType> BLOCKED = Arrays.asList(EntityType.PLAYER, EntityType.MANNEQUIN,
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.ARMOR_STAND);

    /** De soort bij een Minecraft-naam (bv. "fox"), of null als dat geen geschikt wezen is. */
    static EntityType type(String name) {
        EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        if (type == null || !type.isAlive() || !type.isSpawnable() || BLOCKED.contains(type)) return null;
        return type;
    }

    /** Alle soorten die kunnen, voor tab-aanvulling. */
    static List<String> typeNames() {
        List<String> names = new ArrayList<>();
        for (EntityType type : Registry.ENTITY_TYPE) {
            if (type.isAlive() && type.isSpawnable() && !BLOCKED.contains(type)) names.add(type.getKey().getKey());
        }
        Collections.sort(names);
        return names;
    }

    /** Mogelijke varianten voor deze soort (leeg = geen varianten). */
    static List<String> variants(EntityType type) {
        if (type == EntityType.FOX) return enumNames(Fox.Type.values());
        if (type == EntityType.PARROT) return enumNames(Parrot.Variant.values());
        if (type == EntityType.AXOLOTL) return enumNames(Axolotl.Variant.values());
        if (type == EntityType.RABBIT) return enumNames(Rabbit.Type.values());
        if (type == EntityType.HORSE) return enumNames(Horse.Color.values());
        if (type == EntityType.LLAMA || type == EntityType.TRADER_LLAMA) return enumNames(Llama.Color.values());
        if (type == EntityType.SHEEP) return enumNames(DyeColor.values());
        if (type == EntityType.CAT) return keys(Registry.CAT_VARIANT);
        if (type == EntityType.FROG) return keys(Registry.FROG_VARIANT);
        if (type == EntityType.WOLF) return keys(Registry.WOLF_VARIANT);
        if (type == EntityType.VILLAGER) return keys(Registry.VILLAGER_PROFESSION);
        return Collections.emptyList();
    }

    /** Variant toepassen; @return false als die niet bestaat voor dit wezen. */
    static boolean applyVariant(Entity e, String variant) {
        String v = variant.toLowerCase(Locale.ROOT);
        try {
            if (e instanceof Fox) ((Fox) e).setFoxType(Fox.Type.valueOf(v.toUpperCase(Locale.ROOT)));
            else if (e instanceof Parrot) ((Parrot) e).setVariant(Parrot.Variant.valueOf(v.toUpperCase(Locale.ROOT)));
            else if (e instanceof Axolotl) ((Axolotl) e).setVariant(Axolotl.Variant.valueOf(v.toUpperCase(Locale.ROOT)));
            else if (e instanceof Rabbit) ((Rabbit) e).setRabbitType(Rabbit.Type.valueOf(v.toUpperCase(Locale.ROOT)));
            else if (e instanceof Horse) ((Horse) e).setColor(Horse.Color.valueOf(v.toUpperCase(Locale.ROOT)));
            else if (e instanceof Llama) ((Llama) e).setColor(Llama.Color.valueOf(v.toUpperCase(Locale.ROOT)));
            else if (e instanceof Sheep) ((Sheep) e).setColor(DyeColor.valueOf(v.toUpperCase(Locale.ROOT)));
            else if (e instanceof Cat) return set(Registry.CAT_VARIANT, v, t -> ((Cat) e).setCatType(t));
            else if (e instanceof Frog) return set(Registry.FROG_VARIANT, v, t -> ((Frog) e).setVariant(t));
            else if (e instanceof Wolf) return set(Registry.WOLF_VARIANT, v, t -> ((Wolf) e).setVariant(t));
            else if (e instanceof Villager) return set(Registry.VILLAGER_PROFESSION, v, t -> ((Villager) e).setProfession(t));
            else return false;
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** Baby en zitten toepassen (alleen als het wezen dat kan). */
    static void applyPose(Entity e, boolean baby, boolean sit) {
        if (e instanceof Ageable) {
            Ageable a = (Ageable) e;
            if (baby) a.setBaby();
            else a.setAdult();
            a.setAgeLock(true); // niet groot worden
        }
        if (e instanceof Sittable) ((Sittable) e).setSitting(sit);
    }

    static boolean canBeBaby(EntityType type) {
        Class<?> c = type.getEntityClass();
        return c != null && Ageable.class.isAssignableFrom(c);
    }

    static boolean canSit(EntityType type) {
        Class<?> c = type.getEntityClass();
        return c != null && Sittable.class.isAssignableFrom(c);
    }

    private static <T extends Keyed> boolean set(Registry<T> registry, String key, java.util.function.Consumer<T> apply) {
        T value = registry.get(NamespacedKey.minecraft(key));
        if (value == null) return false;
        apply.accept(value);
        return true;
    }

    private static List<String> keys(Registry<? extends Keyed> registry) {
        List<String> names = new ArrayList<>();
        for (Keyed k : registry) names.add(k.getKey().getKey());
        Collections.sort(names);
        return names;
    }

    private static List<String> enumNames(Enum<?>[] values) {
        List<String> names = new ArrayList<>();
        for (Enum<?> v : values) names.add(v.name().toLowerCase(Locale.ROOT));
        return names;
    }
}
