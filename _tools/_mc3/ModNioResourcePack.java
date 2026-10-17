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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.class_2960;
import net.minecraft.class_3255;
import net.minecraft.class_3262;
import net.minecraft.class_3264;
import net.minecraft.class_3270;
import net.minecraft.class_4239;
import net.minecraft.class_7367;

public class ModNioResourcePack implements class_3262, ModResourcePack {
	private static final Logger LOGGER = LoggerFactory.getLogger(ModNioResourcePack.class);
	private static final Pattern RESOURCE_PACK_PATH = Pattern.compile("[a-z0-9-_.]+");
	private static final FileSystem DEFAULT_FS = FileSystems.getDefault();

	private final String id;
	private final ModMetadata modInfo;
	private final List<Path> basePaths;
	private final class_3264 type;
	private final AutoCloseable closer;
	private final ResourcePackActivationType activationType;
	private final Map<class_3264, Set<String>> namespaces;

	public static ModNioResourcePack create(String id, ModContainer mod, String subPath, class_3264 type, ResourcePackActivationType activationType) {
		List<Path> rootPaths = mod.getRootPaths();
		List<Path> paths;

		if (subPath == null) {
			paths = rootPaths;
		} else {
			paths = new ArrayList<>(rootPaths.size());

			for (Path path : rootPaths) {
				path = path.toAbsolutePath().normalize();
				Path childPath = path.resolve(subPath.replace("/", path.getFileSystem().getSeparator())).normalize();

				if (!childPath.startsWith(path) || !exists(childPath)) {
					continue;
				}

				paths.add(childPath);
			}
		}

		if (paths.isEmpty()) return null;

		ModNioResourcePack ret = new ModNioResourcePack(id, mod.getMetadata(), paths, type, null, activationType);

		return ret.method_14406(type).isEmpty() ? null : ret;
	}

	private ModNioResourcePack(String id, ModMetadata modInfo, List<Path> paths, class_3264 type, AutoCloseable closer, ResourcePackActivationType activationType) {
		this.id = id;
		this.modInfo = modInfo;
		this.basePaths = paths;
		this.type = type;
		this.closer = closer;
		this.activationType = activationType;
		this.namespaces = readNamespaces(paths, modInfo.getId());
	}

	static Map<class_3264, Set<String>> readNamespaces(List<Path> paths, String modId) {
		Map<class_3264, Set<String>> ret = new EnumMap<>(class_3264.class);

		for (class_3264 type : class_3264.values()) {
			Set<String> namespaces = null;

			for (Path path : paths) {
				Path dir = path.resolve(type.method_14413());
				if (!Files.isDirectory(dir)) continue;

				String separator = path.getFileSystem().getSeparator();

				try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
					for (Path p : ds) {
						if (!Files.isDirectory(p)) continue;

						String s = p.getFileName().toString();
						// s may contain trailing slashes, remove them
						s = s.replace(separator, "");

						if (!RESOURCE_PACK_PATH.matcher(s).matches()) {
							LOGGER.warn("Fabric NioResourcePack: ignored invalid namespace: {} in mod ID {}", s, modId);
							continue;
						}

						if (namespaces == null) namespaces = new HashSet<>();

						namespaces.add(s);
					}
				} catch (IOException e) {
					LOGGER.warn("getNamespaces in mod " + modId + " failed!", e);
				}
			}

			ret.put(type, namespaces != null ? namespaces : Collections.emptySet());
		}

		return ret;
	}

	private Path getPath(String filename) {
		if (hasAbsentNs(filename)) return null;

		for (Path basePath : basePaths) {
			Path childPath = basePath.resolve(filename.replace("/", basePath.getFileSystem().getSeparator())).toAbsolutePath().normalize();

			if (childPath.startsWith(basePath) && exists(childPath)) {
				return childPath;
			}
		}

		return null;
	}

	private static final String resPrefix = class_3264.field_14188.method_14413() + "/";
	private static final String dataPrefix = class_3264.field_14190.method_14413() + "/";

	private boolean hasAbsentNs(String filename) {
		int prefixLen;
		class_3264 type;

		if (filename.startsWith(resPrefix)) {
			prefixLen = resPrefix.length();
			type = class_3264.field_14188;
		} else if (filename.startsWith(dataPrefix)) {
			prefixLen = dataPrefix.length();
			type = class_3264.field_14190;
		} else {
			return false;
		}

		int nsEnd = filename.indexOf('/', prefixLen);
		if (nsEnd < 0) return false;

		return !namespaces.get(type).contains(filename.substring(prefixLen, nsEnd));
	}

	private class_7367<InputStream> openFile(String filename) {
		Path path = getPath(filename);

		if (path != null && Files.isRegularFile(path)) {
			return () -> Files.newInputStream(path);
		}

		if (ModResourcePackUtil.containsDefault(this.modInfo, filename)) {
			return () -> ModResourcePackUtil.openDefault(this.modInfo, this.type, filename);
		}

		return null;
	}

	@Nullable
	@Override
	public class_7367<InputStream> method_14410(String... pathSegments) {
		class_4239.method_46345(pathSegments);

		return this.openFile(String.join("/", pathSegments));
	}

	@Override
	@Nullable
	public class_7367<InputStream> method_14405(class_3264 type, class_2960 id) {
		final Path path = getPath(getFilename(type, id));
		return path == null ? null : class_7367.create(path);
	}

	@Override
	public void method_14408(class_3264 type, String namespace, String path, class_7664 visitor) {
		if (!namespaces.getOrDefault(type, Collections.emptySet()).contains(namespace)) {
			return;
		}

		for (Path basePath : basePaths) {
			String separator = basePath.getFileSystem().getSeparator();
			Path nsPath = basePath.resolve(type.method_14413()).resolve(namespace);
			Path searchPath = nsPath.resolve(path.replace("/", separator)).normalize();
			if (!exists(searchPath)) continue;

			try {
				Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						String filename = nsPath.relativize(file).toString().replace(separator, "/");
						class_2960 identifier = class_2960.method_43902(namespace, filename);

						if (identifier == null) {
							LOGGER.error("Invalid path in mod resource-pack {}: {}:{}, ignoring", id, namespace, filename);
						} else {
							visitor.accept(identifier, class_7367.create(file));
						}

						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e) {
				LOGGER.warn("findResources at " + path + " in namespace " + namespace + ", mod " + modInfo.getId() + " failed!", e);
			}
		}
	}

	@Override
	public Set<String> method_14406(class_3264 type) {
		return namespaces.getOrDefault(type, Collections.emptySet());
	}

	@Override
	public <T> T method_14407(class_3270<T> metaReader) throws IOException {
		try (InputStream is = openFile("pack.mcmeta").get()) {
			return class_3255.method_14392(metaReader, is);
		}
	}

	@Override
	public void close() {
		if (closer != null) {
			try {
				closer.close();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	@Override
	public ModMetadata getFabricModMetadata() {
		return modInfo;
	}

	public ResourcePackActivationType getActivationType() {
		return this.activationType;
	}

	@Override
	public String method_14409() {
		return id;
	}

	private static boolean exists(Path path) {
		// NIO Files.exists is notoriously slow when checking the file system
		return path.getFileSystem() == DEFAULT_FS ? path.toFile().exists() : Files.exists(path);
	}

	private static String getFilename(class_3264 type, class_2960 id) {
		return String.format(Locale.ROOT, "%s/%s/%s", type.method_14413(), id.method_12836(), id.method_12832());
	}
}

