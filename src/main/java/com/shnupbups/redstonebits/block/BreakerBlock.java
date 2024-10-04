package com.shnupbups.redstonebits.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.component.ComponentChanges;
import net.minecraft.component.DataComponentTypes;
import org.jetbrains.annotations.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.FacingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;

import com.shnupbups.redstonebits.init.RBBlockEntities;
import com.shnupbups.redstonebits.blockentity.BreakerBlockEntity;
import com.shnupbups.redstonebits.screen.handler.BreakerScreenHandler;
import com.shnupbups.redstonebits.properties.RBProperties;

public class BreakerBlock extends BlockWithEntity implements BlockEntityProvider {
	public static final MapCodec<BreakerBlock> CODEC = createCodec(BreakerBlock::new);

	public static final DirectionProperty FACING = FacingBlock.FACING;
	public static final BooleanProperty TRIGGERED = Properties.TRIGGERED;
	public static final BooleanProperty BREAKING = RBProperties.BREAKING;

	public BreakerBlock(Settings settings) {
		super(settings);
		this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(TRIGGERED, false).with(BREAKING, false));
	}

	@Override
	protected MapCodec<? extends BreakerBlock> getCodec() {
		return CODEC;
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return validateTicker(type, RBBlockEntities.BREAKER, world.isClient ? null : BreakerBlockEntity::serverTick);
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new BreakerBlockEntity(pos, state);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof BreakerBlockEntity) {
				player.openHandledScreen(state.createScreenHandlerFactory(world, pos));
			}
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public NamedScreenHandlerFactory createScreenHandlerFactory(BlockState state, World world, BlockPos pos) {
		return new ExtendedScreenHandlerFactory<BreakerScreenHandler.Data>() {
			@Override
			public BreakerScreenHandler.Data getScreenOpeningData(ServerPlayerEntity player) {
				return new BreakerScreenHandler.Data(pos);
			}

			@Override
			public Text getDisplayName() {
				return Text.translatable(getTranslationKey());
			}

			@Override
			public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
				if (world.getBlockEntity(pos) instanceof BreakerBlockEntity breakerBlockEntity) {
					return new BreakerScreenHandler(syncId, playerInventory, breakerBlockEntity);
				} else return null;
			}
		};
	}

	@Override
	public void neighborUpdate(BlockState state, World world, BlockPos pos, Block block, BlockPos pos2, boolean notify) {
		boolean bl = world.isReceivingRedstonePower(pos) || world.isReceivingRedstonePower(pos.up());
		boolean bl2 = state.get(TRIGGERED);
		if (bl && !bl2) {
			world.scheduleBlockTick(pos, this, 4);
			world.setBlockState(pos, state.with(TRIGGERED, true), Block.NO_REDRAW);
		} else if (!bl && bl2) {
			world.setBlockState(pos, state.with(TRIGGERED, false), Block.NO_REDRAW);
		}
		if (isBreaking(world, pos) && pos2 == getBreakPos(world, pos)) {
			cancelBreak(world, pos);
		}
	}

	public boolean startBreak(World world, BlockPos pos) {
		BlockEntity be = world.getBlockEntity(pos);
		BlockState state = world.getBlockState(pos);
		if(state.isAir()) {
			return false;
		}
		BlockState breakState = world.getBlockState(pos.add(state.get(FACING).getVector()));
		boolean isBreakable = !(breakState.getHardness(world, pos) < 0);
		if (be instanceof BreakerBlockEntity breakerBlockEntity && isBreakable) {
			return breakerBlockEntity.startBreak();
		}
		return isBreakable;
	}

	public void cancelBreak(World world, BlockPos pos) {
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof BreakerBlockEntity breakerBlockEntity) {
			breakerBlockEntity.cancelBreak();
		}
	}

	public boolean isBreaking(World world, BlockPos pos) {
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof BreakerBlockEntity breakerBlockEntity) {
			return breakerBlockEntity.isBreaking();
		}
		return false;
	}

	public BlockPos getBreakPos(World world, BlockPos pos) {
		return pos.add(world.getBlockState(pos).get(Properties.FACING).getVector());
	}

	@Override
	public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (this.isBreaking(world, pos)) this.cancelBreak(world, pos);
		else this.startBreak(world, pos);
		boolean powered = world.isReceivingRedstonePower(pos) || world.isReceivingRedstonePower(pos.up());
		boolean triggered = state.get(TRIGGERED);
		if (!powered && triggered) {
			world.setBlockState(pos, state.with(TRIGGERED, false), Block.NO_REDRAW);
		}
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return this.getDefaultState().with(FACING, ctx.getPlayerLookDirection().getOpposite());
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		Text customName = itemStack.get(DataComponentTypes.CUSTOM_NAME);
		if (customName != null) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof BreakerBlockEntity breakerBlockEntity) {
				breakerBlockEntity.readComponents(itemStack);
			}
		}
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity instanceof BreakerBlockEntity breakerBlockEntity) {
				ItemScatterer.spawn(world, pos, breakerBlockEntity);
				world.updateComparators(pos, this);
			}

			super.onStateReplaced(state, world, pos, newState, moved);
		}
	}

	@Override
	public boolean hasComparatorOutput(BlockState state) {
		return true;
	}

	@Override
	public int getComparatorOutput(BlockState state, World world, BlockPos pos) {
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if(blockEntity instanceof BreakerBlockEntity breakerBlockEntity) {
			return (int) (breakerBlockEntity.getBreakPercentage() / (100f / 15f));
		}
		return 0;
	}

	@Override
	public BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public BlockState rotate(BlockState state, BlockRotation rotation) {
		return state.with(FACING, rotation.rotate(state.get(FACING)));
	}

	@Override
	public BlockState mirror(BlockState state, BlockMirror mirror) {
		return state.rotate(mirror.getRotation(state.get(FACING)));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, TRIGGERED, BREAKING);
	}
}
