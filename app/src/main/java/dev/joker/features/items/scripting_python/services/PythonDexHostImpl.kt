package dev.joker.features.items.scripting_python.services

import com.tencent.mm.boot.BuildConfig as WeChatBuildConfig
import dev.joker.features.items.scripting_python.plugin.PythonPluginScope
import dev.joker.python.api.PythonDexHost
import dev.joker.python.api.PythonMemberKind
import dev.joker.python.api.PythonResolvedClass
import dev.joker.python.api.PythonResolvedField
import dev.joker.python.api.PythonResolvedMember
import dev.joker.utils.HostInfo
import dev.joker.utils.reflection.withDexKit
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class PythonDexHostImpl(private val scope: PythonPluginScope) : PythonDexHost {
    override fun findClasses(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): List<PythonResolvedClass> = query {
        val binding = matcher as ClassMatcher
        withDexKit { dexKit ->
            dexKit.findClass {
                applyScope(searchPackages, excludePackages, ignorePackagesCase)
                matcher(binding)
            }.map { data ->
                PythonResolvedClass(data.name, data.descriptor, hostVersion, WeChatBuildConfig.BUILD_TAG)
            }
        }
    }

    override fun findMethods(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): List<PythonResolvedMember> = findMethodData(
        matcher as MethodMatcher,
        searchPackages,
        excludePackages,
        ignorePackagesCase,
        PythonMemberKind.METHOD,
    )

    override fun findConstructors(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): List<PythonResolvedMember> = findMethodData(
        MethodMatcher().allOf(matcher as MethodMatcher, MethodMatcher().name("<init>")),
        searchPackages,
        excludePackages,
        ignorePackagesCase,
        PythonMemberKind.CONSTRUCTOR,
    )

    override fun findFields(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): List<PythonResolvedField> = query {
        val binding = matcher as FieldMatcher
        withDexKit { dexKit ->
            dexKit.findField {
                applyScope(searchPackages, excludePackages, ignorePackagesCase)
                matcher(binding)
            }.map { data ->
                PythonResolvedField(data.descriptor, hostVersion, WeChatBuildConfig.BUILD_TAG)
            }
        }
    }

    private fun findMethodData(
        matcher: MethodMatcher,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
        kind: PythonMemberKind,
    ): List<PythonResolvedMember> = query {
        withDexKit { dexKit ->
            dexKit.findMethod {
                applyScope(searchPackages, excludePackages, ignorePackagesCase)
                matcher(matcher)
            }.map { data ->
                PythonResolvedMember(data.descriptor, hostVersion, WeChatBuildConfig.BUILD_TAG, kind)
            }
        }
    }

    private fun org.luckypray.dexkit.query.FindClass.applyScope(
        search: List<String>,
        exclude: List<String>,
        ignoreCase: Boolean,
    ) {
        if (search.isNotEmpty()) searchPackages(search)
        if (exclude.isNotEmpty()) excludePackages(exclude)
        if (ignoreCase) ignorePackagesCase(true)
    }

    private fun org.luckypray.dexkit.query.FindMethod.applyScope(
        search: List<String>,
        exclude: List<String>,
        ignoreCase: Boolean,
    ) {
        if (search.isNotEmpty()) searchPackages(search)
        if (exclude.isNotEmpty()) excludePackages(exclude)
        if (ignoreCase) ignorePackagesCase(true)
    }

    private fun org.luckypray.dexkit.query.FindField.applyScope(
        search: List<String>,
        exclude: List<String>,
        ignoreCase: Boolean,
    ) {
        if (search.isNotEmpty()) searchPackages(search)
        if (exclude.isNotEmpty()) excludePackages(exclude)
        if (ignoreCase) ignorePackagesCase(true)
    }

    private fun <T> query(block: () -> T): T {
        check(!scope.isClosed) { "Python plugin scope is closed: ${scope.pluginId}" }
        return try {
            executor.submit<T> {
                check(!scope.isClosed) { "Python plugin scope is closed: ${scope.pluginId}" }
                block()
            }.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private val hostVersion get() = "${HostInfo.versionName} (${HostInfo.versionCode})"

    private companion object {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Joker-Python-DexKit").apply { isDaemon = true }
        }
    }
}
