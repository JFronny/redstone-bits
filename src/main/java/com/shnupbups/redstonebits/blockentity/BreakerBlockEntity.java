package com.shnupbups.redstonebits.blockentity;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import com.shnupbups.redstonebits.FakePlayerEntity;
import com.shnupbups.redstonebits.RedstoneBits;
import com.shnupbups.redstonebits.init.RBBlockEntities;
import com.shnupbups.redstonebits.screen.handler.BreakerScreenHandler;
import com.shnupbups.redstonebits.init.RBTags;
import com.shnupbups.redstonebits.properties.RBProperties;

import java.util.Optional;

public class BreakerBlockEntity extends LockableContainerBlockEntity {
	private final PropertyDelegate propertyDelegate = new BreakerPropertyDelegate();
	private DefaultedList<ItemStack> inventory;
	private BlockState breakState;
	private ItemStack breakStack = ItemStack.EMPTY;
	private int breakProgress = 0;
	private FakePlayerEntity fakePlayerEntity;

	public BreakerBlockEntity(BlockPos pos, BlockState state) {
		super(RBBlockEntities.BREAKER, pos, state);
		this.inventory = DefaultedList.ofSize(1, ItemStack.EMPTY);
	}

	public static void serverTick(World world, BlockPos pos, BlockState state, BreakerBlockEntity blockEntity) {
		BlockState currentBreakState = world.getBlockState(blockEntity.getBreakPos());
		if (blockEntity.isBreaking()) {
			BlockState breakState = blockEntity.getBreakState();
			if (breakState == null) blockEntity.startBreak();
			if (breakState == null || (!blockEntity.getBreakStack().equals(blockEntity.getTool()) || !breakState.equals(currentBreakState) || currentBreakState.isAir() || currentBreakState.getHardness(world, pos) < 0)) {
				//System.out.println("cancel");
				blockEntity.cancelBreak();
			} else if (blockEntity.getBreakProgress() >= blockEntity.getBreakTime()) {
				//System.out.println("break");
				blockEntity.finishBreak();
			} else {
				blockEntity.continueBreak();
			}
		}

		if (blockEntity.isBreaking() != world.getBlockState(pos).get(RBProperties.BREAKING)) {
			world.setBlockState(pos, world.getBlockState(pos).with(RBProperties.BREAKING, blockEntity.isBreaking()));
			((ServerChunkManager) world.getChunkManager()).markForUpdate(pos);
			blockEntity.markDirty();
		}
	}

	public void updateCracksAndComparators() {
		if(this.getWorld() == null) return;
		this.getWorld().updateComparators(getPos(), this.getWorld().getBlockState(getPos()).getBlock());

		int breakPercentage = getBreakPercentage();
		int crackProgress = breakPercentage <= 0 || breakPercentage >= 100 ? -1 : breakPercentage / 10;

		this.getWorld().setBlockBreakingInfo(getFakePlayer().getId(), getBreakPos(), crackProgress);
	}

	public static int getBreakPercentage(int breakProgress, int breakTime) {
		if (breakTime > 0) {
			float div = ((float) breakProgress / (float) breakTime);
			return Math.min((int) (div * 100), 100);
		} else return 0;
	}

	public ItemStack getBreakStack() {
		return breakStack;
	}

	public void setBreakStack(ItemStack stack) {
		this.breakStack = stack;
	}

	public BlockState getBreakState() {
		return breakState;
	}

	public void setBreakState(BlockState state) {
		this.breakState = state;
	}

	public int getBreakProgress() {
		return breakProgress;
	}

	public void setBreakProgress(int breakProgress) {
		this.breakProgress = breakProgress;
	}

	public void incrementBreakProgress() {
		this.breakProgress++;
		this.updateCracksAndComparators();
	}

	public void resetBreakProgress() {
		this.breakProgress = 0;
	}

	public PropertyDelegate getPropertyDelegate() {
		return this.propertyDelegate;
	}

	public int getBreakTime() {
		BlockState breakState = this.getBreakState();
		ItemStack stack = this.getBreakStack();
		if (breakState == null) return 0;
		float baseTime = this.calcBlockBreakingTime();
		float itemMultiplier = stack.getMiningSpeedMultiplier(breakState);
		int level = Optional.ofNullable(getWorld())
				.map(s -> s.getRegistryManager().createRegistryLookup())
				.flatMap(s -> s.getOptional(RegistryKeys.ENCHANTMENT))
				.flatMap(s -> s.getOptional(Enchantments.EFFICIENCY))
				.map(s -> EnchantmentHelper.getLevel(s, stack))
				.orElse(0);
		if (level > 0 && itemMultiplier > 1.0f) {
			itemMultiplier += (level * level + 1);
		}
		float time = (baseTime / itemMultiplier) * getBreakTimeMultiplier();
		return (int) time;
	}

	public float getBreakTimeMultiplier() {
		return RedstoneBits.getConfig().breaker().breakTimeMultiplier();
	}

	public boolean isToolEffective() {
		BlockState breakState = this.getBreakState();
		if (breakState == null) return false;
		return !breakState.isToolRequired() || this.getTool().isSuitableFor(breakState);
	}

	public float calcBlockBreakingTime() {
		BlockState breakState = this.getBreakState();
		if (breakState == null) return 0.0F;
		float hardness = breakState.getHardness(this.getWorld(), this.getBreakPos());
		if (hardness == -1.0F) {
			return 0.0F;
		} else {
			int multiplier = isToolEffective() ? 30 : 100;
			return hardness * (float) multiplier;
		}
	}

	public boolean startBreak() {
		//System.out.println("start break at "+getBreakPos().toString());
		BlockState toBreak = this.getWorld().getBlockState(this.getBreakPos());
		ItemStack tool = this.getTool();
		if(toBreak.isIn(RBTags.Blocks.BREAKER_BLACKLIST) || tool.isIn(RBTags.Items.BREAKER_TOOL_BLACKLIST)) {
			return false;
		} else {
			this.setBreakState(toBreak);
			this.setBreakStack(tool);
			this.incrementBreakProgress();
			this.markDirty();
			return true;
		}
	}

	public void cancelBreak() {
		//System.out.println("finish/cancel break at "+getBreakPos().toString());
		this.setBreakState(null);
		this.setBreakStack(ItemStack.EMPTY);
		this.resetBreakProgress();
		this.updateCracksAndComparators();
		this.markDirty();
	}

	public void finishBreak() {
		//System.out.println("finish break at "+getBreakPos().toString());
		this.breakBlock();
		this.cancelBreak();
		this.updateCracksAndComparators();
		this.markDirty();
	}

	public boolean isBreaking() {
		return getBreakProgress() > 0;
	}

	public Direction getFacing() {
		if (this.getWorld() == null || !this.getWorld().getBlockState(this.getPos()).contains(Properties.FACING)) return Direction.NORTH;
		return this.getWorld().getBlockState(this.getPos()).get(Properties.FACING);
	}

	public BlockPos getBreakPos() {
		return this.getPos().add(getFacing().getVector());
	}

	public void breakBlock() {
		//System.out.println("break at "+getBreakPos().toString());
		World world = this.getWorld();
		if (world != null && !world.isClient()) {
			BlockState breakState = this.getBreakState();
			PlayerEntity fakePlayer = this.getFakePlayer();
			BlockEntity blockEntity = breakState.hasBlockEntity() ? world.getBlockEntity(getBreakPos()) : null;
			fakePlayer.setStackInHand(Hand.MAIN_HAND, getBreakStack());
			if (getTool().getItem().canMine(breakState, world, getBreakPos(), fakePlayer) && isToolEffective()) {
				Block.dropStacks(breakState, world, getBreakPos(), blockEntity, fakePlayer, getTool());
			}
			getTool().getItem().postMine(getTool(), world, breakState, getBreakPos(), fakePlayer);
			world.breakBlock(getBreakPos(), false);
		}
	}

	public void continueBreak() {
		this.incrementBreakProgress();
		this.markDirty();
	}

	public ItemStack getTool() {
		return this.getStack(0);
	}

	public PlayerEntity getFakePlayer() {
		if (fakePlayerEntity == null) fakePlayerEntity = new FakePlayerEntity(this.getWorld(), this.getPos());
		return fakePlayerEntity;
	}

	@Override
	public Text getContainerName() {
		return Text.translatable("container.redstonebits.breaker");
	}

	@Override
	protected DefaultedList<ItemStack> getHeldStacks() {
		return inventory;
	}

	@Override
	protected void setHeldStacks(DefaultedList<ItemStack> inventory) {
		this.inventory = inventory;
	}

	@Override
	protected ScreenHandler createScreenHandler(int syncId, PlayerInventory playerInventory) {
		return new BreakerScreenHandler(syncId, playerInventory, this, this.getPropertyDelegate());
	}

	@Override
	public int size() {
		return 1;
	}

	@Override
	public boolean isEmpty() {
		return this.inventory.stream().allMatch(ItemStack::isEmpty);
	}

	@Override
	public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		Inventories.readNbt(nbt, this.inventory, registryLookup);
		this.readBreakerNbt(registryLookup, nbt);
	}

	@Override
	public void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.writeNbt(nbt, registryLookup);
		Inventories.writeNbt(nbt, this.inventory, registryLookup);
		this.writeBreakerNbt(registryLookup, nbt);
	}

	@Override
	public ItemStack getStack(int slot) {
		return this.inventory.get(slot);
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		ItemStack stack = Inventories.splitStack(this.inventory, slot, amount);
		if (!stack.isEmpty()) {
			this.markDirty();
		}

		return stack;
	}

	@Override
	public ItemStack removeStack(int slot) {
		return Inventories.removeStack(this.inventory, slot);
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		this.inventory.set(slot, stack);
		if (stack.getCount() > this.getMaxCountPerStack()) {
			stack.setCount(this.getMaxCountPerStack());
		}

		this.markDirty();
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return Inventory.canPlayerUse(this, player);
	}

	@Override
	public void clear() {
		this.inventory.clear();
	}

	public int getBreakPercentage() {
		if(breakStack == null || breakState == null) return -1;
		return getBreakPercentage(this.getBreakProgress(), this.getBreakTime());
	}

	public void writeBreakerNbt(RegistryWrapper.WrapperLookup wrapperLookup, NbtCompound nbt) {
		nbt.putInt("BreakProgress", this.getBreakProgress());
		if (this.getBreakState() != null) {
			nbt.put("BreakState", NbtHelper.fromBlockState(this.getBreakState()));
		}
		ItemStack.CODEC.encode(this.getBreakStack(), wrapperLookup.getOps(NbtOps.INSTANCE), new NbtCompound())
				.resultOrPartial(error -> RedstoneBits.LOGGER.error("Couldn't serialize breaker stack: {}", error))
				.ifPresent(breakStack -> nbt.put("BreakStack", breakStack));
	}

	public void readBreakerNbt(RegistryWrapper.WrapperLookup wrapperLookup, NbtCompound nbt) {
		this.setBreakProgress(nbt.getInt("BreakProgress"));
		if (nbt.contains("BreakState")) {
			RegistryWrapper<Block> registryEntryLookup = this.world != null ? this.world.createCommandRegistryWrapper(RegistryKeys.BLOCK) : Registries.BLOCK.getReadOnlyWrapper();
			this.setBreakState(NbtHelper.toBlockState(registryEntryLookup, nbt.getCompound("BreakState")));
		} else this.setBreakState(null);
		ItemStack.fromNbt(wrapperLookup, nbt.getCompound("BreakStack"))
				.ifPresent(this::setBreakStack);
	}

	@Override
	public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
		NbtCompound nbt = new NbtCompound();
		writeBreakerNbt(registryLookup, nbt);
		return nbt;
	}

	@Override
	public BlockEntityUpdateS2CPacket toUpdatePacket() {
		return BlockEntityUpdateS2CPacket.create(this);
	}

	private class BreakerPropertyDelegate implements PropertyDelegate {
		@Override
		public int get(int index) {
			return switch (index) {
				case 0 -> BreakerBlockEntity.this.getBreakProgress();
				case 1 -> BreakerBlockEntity.this.getBreakTime();
				default -> 0;
			};
		}

		@Override
		public void set(int index, int value) {
			if (index == 0) {
				BreakerBlockEntity.this.setBreakProgress(value);
			}
		}

		@Override
		public int size() {
			return 2;
		}
	}
}
