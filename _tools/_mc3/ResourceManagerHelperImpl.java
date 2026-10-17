/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.impl.resource.loader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_3264;
import net.minecraft.class_3288;
import net.minecraft.class_3302;
import net.minecraft.class_3545;

public class ResourceManagerHelperImpl implements ResourceManagerHelper {
	private static final Map<class_3264, ResourceManagerHelperImpl> registryMap = new HashMap<>();
	private static final Set<class_3545<class_2561, ModNioResourcePack>> builtinResourcePacks = new HashSet<>();
	private static final Logger LOGGER = LoggerFactory.getLogger(ResourceManagerHelperImpl.class);

	private final Set<class_2960> addedListenerIds = new HashSet<>();
	private final Set<IdentifiableResourceReloadListener> addedListeners = new LinkedHashSet<>();

	public static ResourceManagerHelperImpl get(class_3264 type) {
		return registryMap.computeIfAbsent(type, (t) -> new ResourceManagerHelperImpl());
	}

	/**
	 * Registers a built-in resource pack. Internal implementation.
	 *
	 * @param id             the identifier of the resource pack
	 * @param subPath        the sub path in the mod resources
	 * @param container      the mod container
	 * @param displayName    the display name of the resource pack
	 * @param activationType the activation type of the resource pack
	 * @return {@code true} if successfully registered the resource pack, else {@code false}
	 * @see ResourceManagerHelper#registerBuiltinResourcePack(class_2960, ModContainer, class_2561, ResourcePackActivationType)
	 * @see ResourceManagerHelper#registerBuiltinResourcePack(class_2960, ModContainer, ResourcePackActivationType)
	 */
	public static boolean registerBuiltinResourcePack(class_2960 id, String subPath, ModContainer container, class_2561 displayName, ResourcePackActivationType activationType) {
		// Assuming the mod has multiple paths, we simply "hope" that the  file separator is *not* different across them
		List<Path> paths = container.getRootPaths();
		String separator = paths.get(0).getFileSystem().getSeparator();
		subPath = subPath.replace("/", separator);
		ModNioResourcePack resourcePack = ModNioResourcePack.create(id.toString(), container, subPath, class_3264.field_14188, activationType);
		ModNioResourcePack dataPack = ModNioResourcePack.create(id.toString(), container, subPath, class_3264.field_14190, activationType);
		if (resourcePack == null && dataPack == null) return false;

		if (resourcePack != null) {
			builtinResourcePacks.add(new class_3545<>(displayName, resourcePack));
		}

		if (dataPack != null) {
			builtinResourcePacks.add(new class_3545<>(displayName, dataPack));
		}

		return true;
	}

	/**
	 * Registers a built-in resource pack. Internal implementation.
	 *
	 * @param id             the identifier of the resource pack
	 * @param subPath        the sub path in the mod resources
	 * @param container      the mod container
	 * @param activationType the activation type of the resource pack
	 * @return {@code true} if successfully registered the resource pack, else {@code false}
	 * @see ResourceManagerHelper#registerBuiltinResourcePack(class_2960, ModContainer, ResourcePackActivationType)
	 * @see ResourceManagerHelper#registerBuiltinResourcePack(class_2960, ModContainer, class_2561, ResourcePackActivationType)
	 */
	public static boolean registerBuiltinResourcePack(class_2960 id, String subPath, ModContainer container, ResourcePackActivationType activationType) {
		return registerBuiltinResourcePack(id, subPath, container, class_2561.method_43470(id.method_12836() + "/" + id.method_12832()), activationType);
	}

	public static void registerBuiltinResourcePacks(class_3264 resourceType, Consumer<class_3288> consumer) {
		// Loop through each registered built-in resource packs and add them if valid.
		for (class_3545<class_2561, ModNioResourcePack> entry : builtinResourcePacks) {
			ModNioResourcePack pack = entry.method_15441();

			// Add the built-in pack only if namespaces for the specified resource type are present.
			if (!pack.method_14406(resourceType).isEmpty()) {
				// Make the resource pack profile for built-in pack, should never be always enabled.
				class_3288 profile = class_3288.method_45275(
						entry.method_15441().method_14409(),
						entry.method_15442(),
						pack.getActivationType() == ResourcePackActivationType.ALWAYS_ENABLED,
						ignored -> entry.method_15441(),
						resourceType,
						class_3288.class_3289.field_14280,
						new BuiltinModResourcePackSource(pack.getFabricModMetadata().getName())
				);
				consumer.accept(profile);
			}
		}
	}

	public static List<class_3302> sort(class_3264 type, List<class_3302> listeners) {
		if (type == null) {
			return listeners;
		}

		ResourceManagerHelperImpl instance = get(type);

		if (instance != null) {
			List<class_3302> mutable = new ArrayList<>(listeners);
			instance.sort(mutable);
			return Collections.unmodifiableList(mutable);
		}

		return listeners;
	}

	protected void sort(List<class_3302> listeners) {
		listeners.removeAll(addedListeners);

		// General rules:
		// - We *do not* touch the ordering of vanilla listeners. Ever.
		//   While dependency values are provided where possible, we cannot
		//   trust them 100%. Only code doesn't lie.
		// - We addReloadListener all custom listeners after vanilla listeners. Same reasons.

		List<IdentifiableResourceReloadListener> listenersToAdd = Lists.newArrayList(addedListeners);
		Set<class_2960> resolvedIds = new HashSet<>();

		for (class_3302 listener : listeners) {
			if (listener instanceof IdentifiableResourceReloadListener) {
				resolvedIds.add(((IdentifiableResourceReloadListener) listener).getFabricId());
			}
		}

		int lastSize = -1;

		while (listeners.size() != lastSize) {
			lastSize = listeners.size();

			Iterator<IdentifiableResourceReloadListener> it = listenersToAdd.iterator();

			while (it.hasNext()) {
				IdentifiableResourceReloadListener listener = it.next();

				if (resolvedIds.containsAll(listener.getFabricDependencies())) {
					resolvedIds.add(listener.getFabricId());
					listeners.add(listener);
					it.remove();
				}
			}
		}

		for (IdentifiableResourceReloadListener listener : listenersToAdd) {
			LOGGER.warn("Could not resolve dependencies for listener: " + listener.getFabricId() + "!");
		}
	}

	@Override
	public void registerReloadListener(IdentifiableResourceReloadListener listener) {
		if (!addedListenerIds.add(listener.getFabricId())) {
			LOGGER.warn("Tried to register resource reload listener " + listener.getFabricId() + " twice!");
			return;
		}

		if (!addedListeners.add(listener)) {
			throw new RuntimeException("Listener with previously unknown ID " + listener.getFabricId() + " already in listener set!");
		}
	}
}

