package io.github.tjhgibbs.savepoint.gametest;

import io.github.tjhgibbs.savepoint.core.Manifest;
import io.github.tjhgibbs.savepoint.mod.SavepointService;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Stress test for data fidelity: fills chests with shulker boxes packed with heavily-loaded items (every
 * enchantment at odd levels, every attribute with every operation, max lore of incompressible text, custom_data
 * with long/int/byte arrays, extreme numbers, deep nesting, near-limit strings, astral-plane unicode), plus a
 * configured spawner and an equipped armor stand. The test chunk ends up over 1 MiB compressed, so it is stored
 * in an external {@code .mcc} file. Then: take a savepoint, restore it into an empty directory, and decode
 * every chunk of every region file with the game's own reader, comparing it with the live save.
 * <p>One-off check, deliberately NOT registered in the gametest fabric.mod.json (slow; see PLAN.md
 * "Checked once: complex NBT fidelity"). Add it to the {@code fabric-gametest} entrypoints to rerun.
 */
public class ComplexNbtGameTests {
    private static final int SHULKERS_PER_CHEST = 27;
    private static final int ITEMS_PER_SHULKER = 27;
    private static final int CHESTS = 2;

    @GameTest(structure = "savepoint-gametest:empty64", maxTicks = 400)
    public void complexNbtSurvivesSavepointRoundTrip(GameTestHelper h) {
        try {
            run(h);
            h.succeed();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void run(GameTestHelper h) throws IOException {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        HolderLookup.Provider reg = level.registryAccess();
        RandomSource rnd = RandomSource.create(1234);

        // --- build the world content
        Map<BlockPos, CompoundTag> expected = new HashMap<>();
        for (int c = 0; c < CHESTS; c++) {
            BlockPos rel = new BlockPos(1 + c, 1, 1);
            h.setBlock(rel, Blocks.CHEST);
            ChestBlockEntity chest = h.getBlockEntity(rel, ChestBlockEntity.class);
            for (int s = 0; s < SHULKERS_PER_CHEST; s++) {
                List<ItemStack> inner = new ArrayList<>();
                for (int i = 0; i < ITEMS_PER_SHULKER; i++) inner.add(heavyItem(reg, rnd, c * 10000 + s * 100 + i));
                ItemStack box = new ItemStack(Items.SHULKER_BOX, 1);
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inner));
                box.set(DataComponents.CUSTOM_NAME, Component.literal("Box " + c + "/" + s + " 📦"));
                chest.setItem(s, box);
            }
            chest.setChanged();
        }

        BlockPos spawnerAbs = h.absolutePos(new BlockPos(4, 1, 1));
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "setblock " + spawnerAbs.getX() + " " + spawnerAbs.getY() + " " + spawnerAbs.getZ()
                        + " minecraft:spawner{Delay:123s,MinSpawnDelay:7s,MaxSpawnDelay:9999s,SpawnCount:12s,"
                        + "MaxNearbyEntities:40s,RequiredPlayerRange:64s,SpawnRange:9s,"
                        + "SpawnData:{entity:{id:\"minecraft:zombie\",CustomName:\"Bob é中\",Health:15.5f,"
                        + "equipment:{mainhand:{id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:sharpness\":255}}}}}},"
                        + "SpawnPotentials:[{weight:3,data:{entity:{id:\"minecraft:skeleton\"}}},"
                        + "{weight:1,data:{entity:{id:\"minecraft:creeper\",powered:1b,ExplosionRadius:100b}}}]}");

        BlockPos standAbs = h.absolutePos(new BlockPos(6, 1, 1));
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "summon minecraft:armor_stand " + standAbs.getX() + " " + standAbs.getY() + " " + standAbs.getZ() + " {Tags:[\"savepoint_nbt\"]}");
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class, new net.minecraft.world.phys.AABB(standAbs).inflate(1));
        if (stands.size() != 1) h.fail("expected one armor stand, got " + stands.size());
        ArmorStand stand = stands.get(0);
        stand.setCustomName(Component.literal("Stand 🛡"));
        stand.setItemSlot(EquipmentSlot.MAINHAND, heavyItem(reg, rnd, -1));
        stand.setItemSlot(EquipmentSlot.HEAD, heavyItem(reg, rnd, -2));

        for (int c = 0; c < CHESTS; c++) expectBe(h, reg, expected, new BlockPos(1 + c, 1, 1));
        expectBe(h, reg, expected, new BlockPos(4, 1, 1));
        CompoundTag spawnerTag = expected.get(spawnerAbs);
        if (spawnerTag.getListOrEmpty("SpawnPotentials").size() != 2 || spawnerTag.getShortOr("Delay", (short) 0) != 123)
            h.fail("spawner SNBT did not apply: " + spawnerTag);

        // --- savepoint, then restore into an empty directory
        Path root = Files.createTempDirectory("savepoint-nbt-store");
        SavepointService svc = SavepointService.startForTesting(server, root);
        Manifest m = svc.createUndoPoint(null, "nbt");
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path liveCopy = Files.createTempDirectory("savepoint-nbt-live");
        copyRegions(world, liveCopy); // read copies so the game's reader never opens the live files
        Path restored = Files.createTempDirectory("savepoint-nbt-restored");
        svc.store().restore(restored, m.id());

        if (m.entries().keySet().stream().noneMatch(k -> k.endsWith(".mcc")))
            h.fail("stress data did not produce an oversized (.mcc) chunk; entries: " + m.entries().keySet());

        // --- every chunk of every region file decodes to identical NBT
        int compared = 0;
        Map<BlockPos, CompoundTag> foundBes = new HashMap<>();
        boolean foundStand = false;
        for (String rel : m.entries().keySet()) {
            if (!rel.endsWith(".mca")) continue;
            String[] p = Path.of(rel).getFileName().toString().split("\\.");
            int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
            try (RegionFile a = open(liveCopy.resolve(rel)); RegionFile b = open(restored.resolve(rel))) {
                for (int lx = 0; lx < 32; lx++)
                    for (int lz = 0; lz < 32; lz++) {
                        ChunkPos cp = new ChunkPos(rx * 32 + lx, rz * 32 + lz);
                        CompoundTag ta = read(a, cp), tb = read(b, cp);
                        if ((ta == null) != (tb == null)) h.fail(rel + " " + cp + ": present in only one of live/restored");
                        if (ta == null) continue;
                        if (!ta.equals(tb)) h.fail(rel + " " + cp + ": NBT differs after restore");
                        compared++;
                        for (Tag t : tb.getListOrEmpty("block_entities")) {
                            CompoundTag be = (CompoundTag) t;
                            foundBes.put(new BlockPos(be.getIntOr("x", 0), be.getIntOr("y", 0), be.getIntOr("z", 0)), be);
                        }
                        for (Tag t : tb.getListOrEmpty("Entities"))
                            if ("minecraft:armor_stand".equals(((CompoundTag) t).getStringOr("id", ""))) foundStand = true;
                    }
            }
        }
        if (compared == 0) h.fail("no chunks compared");
        if (!foundStand) h.fail("armor stand not found in restored entity chunks");

        // --- the restored block entities equal what the game held in memory, and load back losslessly
        for (var e : expected.entrySet()) {
            CompoundTag got = foundBes.get(e.getKey());
            if (got == null) h.fail("block entity at " + e.getKey() + " missing from restored chunks");
            got = got.copy();
            got.remove("keepPacked"); // added by the chunk serializer, not part of the block entity's own data
            if (!got.equals(e.getValue())) h.fail("block entity at " + e.getKey() + " differs from in-memory state: " + diff(e.getValue(), got));
            BlockEntity reloaded = BlockEntity.loadStatic(e.getKey(), level.getBlockState(e.getKey()), got, reg);
            if (reloaded == null || !reloaded.saveWithFullMetadata(reg).equals(e.getValue()))
                h.fail("block entity at " + e.getKey() + " does not reload to the same data");
        }
        long mcc = 0;
        for (String k : m.entries().keySet()) if (k.endsWith(".mcc")) mcc += Files.size(restored.resolve(k));
        System.out.println("[savepoint-nbt] chunks compared: " + compared + ", block entities checked: " + expected.size()
                + ", external .mcc bytes: " + mcc);
    }

    private static void expectBe(GameTestHelper h, HolderLookup.Provider reg, Map<BlockPos, CompoundTag> out, BlockPos rel) {
        BlockEntity be = h.getBlockEntity(rel, BlockEntity.class);
        out.put(be.getBlockPos(), be.saveWithFullMetadata(reg));
    }

    private static ItemStack heavyItem(HolderLookup.Provider reg, RandomSource rnd, int n) {
        ItemStack st = new ItemStack(n % 3 == 0 ? Items.NETHERITE_SWORD : n % 3 == 1 ? Items.LEATHER_CHESTPLATE : Items.TRIDENT, 1);

        ItemEnchantments.Mutable ench = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        int lvl = 1;
        for (Holder<Enchantment> e : (Iterable<Holder.Reference<Enchantment>>) reg.lookupOrThrow(Registries.ENCHANTMENT).listElements()::iterator)
            ench.set(e, (lvl++ * 37) % 255 + 1);
        st.set(DataComponents.ENCHANTMENTS, ench.toImmutable());

        ItemAttributeModifiers.Builder mods = ItemAttributeModifiers.builder();
        EquipmentSlotGroup[] slots = EquipmentSlotGroup.values();
        int k = 0;
        for (Holder<Attribute> a : (Iterable<Holder.Reference<Attribute>>) reg.lookupOrThrow(Registries.ATTRIBUTE).listElements()::iterator)
            for (AttributeModifier.Operation op : AttributeModifier.Operation.values()) {
                double amt = switch (k % 4) {
                    case 0 -> Double.MAX_VALUE;
                    case 1 -> -Double.MIN_VALUE;
                    case 2 -> 1e-300;
                    default -> rnd.nextGaussian() * 1e9;
                };
                mods.add(a, new AttributeModifier(Identifier.fromNamespaceAndPath("savepoint_test", "m" + n + "_" + k), amt, op), slots[k % slots.length]);
                k++;
            }
        st.set(DataComponents.ATTRIBUTE_MODIFIERS, mods.build());

        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < 4; i++) lore.add(Component.literal(randomText(rnd, 300)).withStyle(ChatFormatting.values()[i % 16]));
        st.set(DataComponents.LORE, new ItemLore(lore));
        st.set(DataComponents.CUSTOM_NAME, Component.literal("Item #" + n + " 𐍈 \u0000 null char").withStyle(ChatFormatting.BOLD));
        st.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        st.set(DataComponents.DAMAGE, 7);
        if (st.is(Items.LEATHER_CHESTPLATE)) st.set(DataComponents.DYED_COLOR, new DyedItemColor(0xFFFFFFFF));

        CompoundTag data = new CompoundTag();
        long[] longs = new long[64];
        longs[0] = Long.MIN_VALUE;
        longs[1] = Long.MAX_VALUE;
        longs[2] = -1L;
        for (int i = 3; i < longs.length; i++) longs[i] = rnd.nextLong();
        data.put("longs", new LongArrayTag(longs));
        data.putLong("minLong", Long.MIN_VALUE);
        data.putLong("maxLong", Long.MAX_VALUE);
        data.put("ints", new IntArrayTag(new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1}));
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) rnd.nextInt(256);
        data.put("bytes", new ByteArrayTag(bytes));
        ListTag doubles = new ListTag();
        for (double d : new double[] {Double.MAX_VALUE, Double.MIN_VALUE, -0.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            doubles.add(DoubleTag.valueOf(d));
        data.put("doubles", doubles);
        CompoundTag deep = data;
        for (int i = 0; i < 64; i++) {
            CompoundTag next = new CompoundTag();
            deep.put("d", next);
            deep = next;
        }
        deep.putString("bottom", "😀 emoji at depth 64");
        if (n == 0) data.putString("nearMaxString", randomText(rnd, 60000)); // NBT strings cap at 65535 UTF-8 bytes
        st.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return st;
    }

    private static String diff(CompoundTag want, CompoundTag got) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> keys = new java.util.TreeSet<>(want.keySet());
        keys.addAll(got.keySet());
        for (String k : keys) {
            Tag a = want.get(k), b = got.get(k);
            if (java.util.Objects.equals(a, b)) continue;
            String as = String.valueOf(a), bs = String.valueOf(b);
            sb.append(k).append(": in-memory=").append(as.length() > 300 ? as.substring(0, 300) + "..." : as)
              .append(" restored=").append(bs.length() > 300 ? bs.substring(0, 300) + "..." : bs).append("; ");
        }
        return sb.toString();
    }

    private static String randomText(RandomSource rnd, int len) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static RegionFile open(Path mca) throws IOException {
        return new RegionFile(new RegionStorageInfo("savepoint-test", Level.OVERWORLD, "chunk"), mca, mca.getParent(), false);
    }

    private static CompoundTag read(RegionFile f, ChunkPos cp) throws IOException {
        try (DataInputStream in = f.getChunkDataInputStream(cp)) {
            return in == null ? null : NbtIo.read(in);
        }
    }

    private static void copyRegions(Path world, Path dest) throws IOException {
        try (Stream<Path> s = Files.walk(world)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String n = f.getFileName().toString();
                if (!Files.isRegularFile(f) || !(n.endsWith(".mca") || n.endsWith(".mcc"))) continue;
                Path t = dest.resolve(world.relativize(f).toString());
                Files.createDirectories(t.getParent());
                Files.copy(f, t);
            }
        }
    }
}
