package com.minecolonies.kingdoms.citizen;

import java.util.UUID;

/** Kingdoms-owned deterministic name pools; the same representative id always yields the same name. */
public final class CitizenNames
{
    private static final String[] MALE = {"Aldric", "Bram", "Cedric", "Dunstan", "Edmund", "Florian", "Godric", "Hale",
        "Ivo", "Jory", "Kellan", "Leofric", "Matthias", "Nils", "Osric", "Percival", "Quill", "Roland", "Silas", "Tobin",
        "Ulric", "Victor", "Wendel", "Yorick", "Anselm", "Berold", "Corin", "Darian", "Emeric", "Fenwick", "Gareth",
        "Hamond"};
    private static final String[] FEMALE = {"Adela", "Beatrix", "Cecily", "Dorothea", "Edith", "Fayette", "Gisela",
        "Hilda", "Isolde", "Joan", "Katrin", "Linnea", "Mabel", "Nesta", "Odile", "Petra", "Rosalind", "Sabine",
        "Tamsin", "Ursula", "Vivienne", "Wynne", "Yseult", "Agnes", "Brenna", "Clemence", "Delia", "Elspeth", "Freya",
        "Gwen", "Helena", "Ida"};
    private static final String[] FAMILY = {"Ashdown", "Barrow", "Carter", "Dunmore", "Elwood", "Fairbairn", "Glover",
        "Hawthorne", "Ingram", "Joyner", "Kestrel", "Langley", "Miller", "Northcott", "Oakes", "Pryor", "Quarrie",
        "Redmayne", "Smith", "Thatcher", "Underhill", "Vance", "Wainwright", "Yeoman", "Brook", "Cooper", "Fletcher",
        "Mason", "Tanner", "Weaver", "Wright", "Holt"};

    private CitizenNames() {}

    public static String name(final UUID id, final boolean female)
    {
        final long bits = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
        final String[] given = female ? FEMALE : MALE;
        return given[Math.floorMod(bits, given.length)] + ' ' + FAMILY[Math.floorMod(bits >>> 23, FAMILY.length)];
    }

    public static boolean female(final UUID id)
    {
        return Math.floorMod(id.getLeastSignificantBits() >>> 7, 2) == 1;
    }

    public static long cosmeticSeed(final UUID id)
    {
        return id.getMostSignificantBits() * 31L + id.getLeastSignificantBits();
    }
}
