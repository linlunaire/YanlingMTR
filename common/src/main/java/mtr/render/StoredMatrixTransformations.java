package mtr.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mtr.MTRClient;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class StoredMatrixTransformations {

	private static long irisCheckFrame = Long.MIN_VALUE;
	private static boolean shaderPackInUse;

	private final BlockAnchor anchor;
	private final List<Consumer<PoseStack>> transformations = new ArrayList<>();

	public StoredMatrixTransformations() {
		this(null);
	}

	private StoredMatrixTransformations(BlockAnchor anchor) {
		this.anchor = anchor;
	}

	public static StoredMatrixTransformations atBlockEntity(PoseStack matrices, BlockPos pos, double x, double y, double z) {
		return atBlockEntity(matrices, null, pos, x, y, z);
	}

	static StoredMatrixTransformations atBlockEntity(PoseStack matrices, MultiBufferSource buffers, BlockPos pos, double x, double y, double z) {
		final long frame = MTRClient.getRenderFrame();
		if (irisCheckFrame != frame) {
			shaderPackInUse = IrisShaderStatus.ACTIVE.getAsBoolean();
			irisCheckFrame = frame;
		}
		// The vanilla buffer flushes on texture changes. Keep its world-space batching.
		return new StoredMatrixTransformations(new BlockAnchor(shaderPackInUse ? matrices : null, shaderPackInUse ? buffers : null, pos, x, y, z));
	}

	public void add(Consumer<PoseStack> transformation) {
		transformations.add(transformation);
	}

	public void transform(PoseStack matrices) {
		matrices.pushPose();
		try {
			apply(matrices, false);
		} catch (RuntimeException | Error failure) {
			matrices.popPose();
			throw failure;
		}
	}

	public StoredMatrixTransformations copy() {
		final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(anchor);
		storedMatrixTransformations.transformations.addAll(transformations);
		return storedMatrixTransformations;
	}

	public void draw(MultiBufferSource buffers, ResourceLocation texture, RenderTrains.QueuedRenderLayer layer, BiConsumer<PoseStack, VertexConsumer> renderer) {
		draw(buffers, texture, layer, false, renderer);
	}

	void draw(ResourceLocation texture, RenderTrains.QueuedRenderLayer layer, BiConsumer<PoseStack, VertexConsumer> renderer) {
		draw(anchor == null ? null : anchor.buffers, texture, layer, false, renderer);
	}

	public void draw(MultiBufferSource buffers, ResourceLocation texture, RenderTrains.QueuedRenderLayer layer, boolean priority, BiConsumer<PoseStack, VertexConsumer> renderer) {
		final PoseStack matrices = anchor == null ? null : anchor.localPose;
		if (matrices == null || buffers == null) {
			RenderTrains.scheduleRender(texture, priority, layer, (worldPose, vertices) -> {
				transform(worldPose);
				try {
					renderer.accept(worldPose, vertices);
				} finally {
					worldPose.popPose();
				}
			});
			return;
		}

		// Submit while Iris still has the block entity's buffer and render pass.
		final VertexConsumer vertices = buffers.getBuffer(layer.renderType(texture));
		matrices.pushPose();
		try {
			apply(matrices, true);
			renderer.accept(matrices, vertices);
		} finally {
			matrices.popPose();
		}
	}

	private void apply(PoseStack matrices, boolean local) {
		if (anchor != null) {
			if (local) {
				matrices.translate(anchor.x, anchor.y, anchor.z);
			} else {
				matrices.translate(anchor.x + anchor.pos.getX(), anchor.y + anchor.pos.getY(), anchor.z + anchor.pos.getZ());
			}
		}
		for (final Consumer<PoseStack> transformation : transformations) {
			transformation.accept(matrices);
		}
	}

	private record BlockAnchor(PoseStack localPose, MultiBufferSource buffers, BlockPos pos, double x, double y, double z) {}

	private static final class IrisShaderStatus {
		private static final BooleanSupplier ACTIVE = find();

		private static BooleanSupplier find() {
			try {
				final Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				final Object instance = api.getMethod("getInstance").invoke(null);
				final Method active = api.getMethod("isShaderPackInUse");
				return () -> {
					try {
						return Boolean.TRUE.equals(active.invoke(instance));
					} catch (ReflectiveOperationException | LinkageError ignored) {
						return false;
					}
				};
			} catch (ReflectiveOperationException | LinkageError ignored) {
				return () -> false;
			}
		}
	}
}
