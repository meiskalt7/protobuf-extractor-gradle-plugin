package com.google.protobuf.gradle

import com.android.build.api.dsl.AndroidSourceSet
import com.android.build.gradle.api.AndroidSourceSet as DeprecatedAndroidSourceSet
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSet
import org.gradle.kotlin.dsl.get

/**
 * Applies the supplied action to the "proto" [SourceDirectorySet] extension on
 * a receiver of type [SourceSet].
 *
 * @since 0.8.7
 * @usage
 * ```
 * sourceSets {
 *     create("sample") {
 *         proto {
 *             srcDir("src/sample/protobuf")
 *         }
 *     }
 * }
 * ```
 *
 * @receiver [SourceSet] The source set for which the "proto" [SourceDirectorySet] extension
 * will be configured
 *
 * @param action A configuration lambda to apply on a receiver of type [SourceDirectorySet]
 * @return [Unit]
 */
fun SourceSet.proto(action: SourceDirectorySet.() -> Unit) {
    (this as? ExtensionAware)
        ?.extensions
        ?.getByName("proto")
        ?.let { it as? SourceDirectorySet }
        ?.apply(action)
}

/**
 * Applies the supplied action to the "proto" [SourceDirectorySet] extension on
 * a receiver of type [DeprecatedAndroidSourceSet] for Android builds.
 *
 * @since 0.8.15
 * @usage
 * ```
 * android {
 *     sourceSets {
 *         create("sample") {
 *             proto {
 *                 srcDir("src/sample/protobuf")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @receiver [DeprecatedAndroidSourceSet] The Android source set for which the "proto"
 * [SourceDirectorySet] extension will be configured
 *
 * @param action A configuration lambda to apply on a receiver of type [SourceDirectorySet]
 * @return [Unit]
 */
fun DeprecatedAndroidSourceSet.proto(action: SourceDirectorySet.() -> Unit) {
    (this as? ExtensionAware)
        ?.extensions
        ?.getByName("proto")
        ?.let { it as?  SourceDirectorySet }
        ?.apply(action)
}

/**
 * Applies the supplied action to the "proto" [SourceDirectorySet] extension on
 * a receiver of type [AndroidSourceSet] for Android builds.
 *
 * @since 0.9.0
 * @usage
 * ```
 * android {
 *     sourceSets {
 *         create("sample") {
 *             proto {
 *                 srcDir("src/sample/protobuf")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @receiver [AndroidSourceSet] The Android source set for which the "proto"
 * [SourceDirectorySet] extension will be configured
 *
 * @param action A configuration lambda to apply on a receiver of type [SourceDirectorySet]
 * @return [Unit]
 */
fun AndroidSourceSet.proto(action: SourceDirectorySet.() -> Unit) {
    (this as? ExtensionAware)
        ?.extensions
        ?.getByName("proto")
        ?.let { it as? SourceDirectorySet }
        ?.apply(action)
}
