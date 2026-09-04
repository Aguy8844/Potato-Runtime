package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Development/bootstrap shader compiler.
 *
 * <p>Human-readable GLSL remains the source of truth in resources. Shaderc
 * compiles those assets to SPIR-V for the probe. The production renderer may
 * later replace this strategy with build-time compilation and persistent
 * pipeline caches without changing shader ownership or pipeline APIs.</p>
 */
final class VulkanShaderCompiler {
    private VulkanShaderCompiler() {
    }

    static SpirvBinary compileResource(
            String resourcePath,
            int shaderKind,
            JsonObject report
    ) {
        String source = readUtf8Resource(resourcePath);

        long compiler = shaderc_compiler_initialize();

        if (compiler == NULL) {
            throw new VulkanProbeException(
                    "COMPILE_SHADERS",
                    "shaderc_compiler_initialize returned NULL."
            );
        }

        long options = shaderc_compile_options_initialize();

        if (options == NULL) {
            shaderc_compiler_release(compiler);

            throw new VulkanProbeException(
                    "COMPILE_SHADERS",
                    "shaderc_compile_options_initialize returned NULL."
            );
        }

        long result = NULL;

        try {
            shaderc_compile_options_set_target_env(
                    options,
                    shaderc_target_env_vulkan,
                    shaderc_env_version_vulkan_1_3
            );

            shaderc_compile_options_set_target_spirv(
                    options,
                    shaderc_spirv_version_1_6
            );

            shaderc_compile_options_set_optimization_level(
                    options,
                    shaderc_optimization_level_performance
            );

            shaderc_compile_options_set_warnings_as_errors(
                    options
            );

            result = shaderc_compile_into_spv(
                    compiler,
                    source,
                    shaderKind,
                    resourcePath,
                    "main",
                    options
            );

            if (result == NULL) {
                throw new VulkanProbeException(
                        "COMPILE_SHADERS",
                        "Shaderc returned a null compilation result for "
                                + resourcePath
                );
            }

            int status =
                    shaderc_result_get_compilation_status(result);

            long warningCount =
                    shaderc_result_get_num_warnings(result);

            long errorCount =
                    shaderc_result_get_num_errors(result);

            report.addProperty(
                    "shaderWarnings_" + safeKey(resourcePath),
                    warningCount
            );
            report.addProperty(
                    "shaderErrors_" + safeKey(resourcePath),
                    errorCount
            );

            if (status != shaderc_compilation_status_success) {
                String message =
                        shaderc_result_get_error_message(result);

                throw new VulkanProbeException(
                        "COMPILE_SHADERS",
                        "Shader compilation failed for "
                                + resourcePath
                                + ": "
                                + message
                );
            }

            long byteLength =
                    shaderc_result_get_length(result);

            if (byteLength <= 0 || byteLength > Integer.MAX_VALUE) {
                throw new VulkanProbeException(
                        "COMPILE_SHADERS",
                        "Invalid SPIR-V size for "
                                + resourcePath
                                + ": "
                                + byteLength
                );
            }

            ByteBuffer nativeBytes =
                    shaderc_result_get_bytes(
                            result,
                            byteLength
                    );

            if (nativeBytes == null) {
                throw new VulkanProbeException(
                        "COMPILE_SHADERS",
                        "Shaderc returned null SPIR-V bytes for "
                                + resourcePath
                );
            }

            ByteBuffer ownedCopy =
                    MemoryUtil.memAlloc((int) byteLength);

            ownedCopy.put(nativeBytes.duplicate());
            ownedCopy.flip();

            report.addProperty(
                    "spirvBytes_" + safeKey(resourcePath),
                    byteLength
            );

            return new SpirvBinary(
                    resourcePath,
                    ownedCopy
            );
        } finally {
            if (result != NULL) {
                shaderc_result_release(result);
            }

            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private static String readUtf8Resource(
            String resourcePath
    ) {
        ClassLoader loader =
                VulkanShaderCompiler.class.getClassLoader();

        try (InputStream input =
                     loader.getResourceAsStream(resourcePath)) {

            if (input == null) {
                throw new VulkanProbeException(
                        "LOAD_SHADER_SOURCE",
                        "Shader resource not found: " + resourcePath
                );
            }

            return new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new VulkanProbeException(
                    "LOAD_SHADER_SOURCE",
                    "Could not read shader resource "
                            + resourcePath
                            + ": "
                            + exception.getMessage()
            );
        }
    }

    private static String safeKey(String resourcePath) {
        return resourcePath
                .replace('/', '_')
                .replace('.', '_')
                .replace('-', '_');
    }

    static final class SpirvBinary implements AutoCloseable {
        private final String source;
        private ByteBuffer bytes;

        SpirvBinary(
                String source,
                ByteBuffer bytes
        ) {
            this.source = source;
            this.bytes = bytes;
        }

        ByteBuffer bytes() {
            if (bytes == null) {
                throw new IllegalStateException(
                        "SPIR-V binary already closed: " + source
                );
            }

            return bytes;
        }

        @Override
        public void close() {
            if (bytes != null) {
                MemoryUtil.memFree(bytes);
                bytes = null;
            }
        }
    }
}