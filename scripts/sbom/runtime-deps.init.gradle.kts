// Lists the Maven components packed into the release APK, the dependencies between them,
// and their POMs (with parent POMs, which may hold the licence), for scripts/sbom.sh.
// Applied with -I only when making the SBOM: the app build never loads it. POM downloads
// are checked against gradle/verification-metadata.xml like every other download.
//
// Output lines:  ship <g:a:v>  |  dep <g:a:v or root> <g:a:v>  |  pom <g:a:v> <file>

import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

allprojects {
    if (path != ":app") return@allprojects
    tasks.register("sbomRuntimeDeps") {
        doLast {
            val classpath = configurations.getByName("releaseRuntimeClasspath").incoming
            // The classes that get dexed: one artifact per shipped component, with
            // Kotlin Multiplatform modules already resolved to their -android/-jvm variant.
            val shipped = classpath.artifactView {
                attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
                componentFilter { it is ModuleComponentIdentifier }
            }.artifacts.map { it.id.componentIdentifier as ModuleComponentIdentifier }
                .associateBy { it.displayName }.toSortedMap()
            shipped.keys.forEach { println("ship $it") }

            // Edges between shipped components, through the ones that ship nothing themselves
            // (:core, platforms/BOMs, Kotlin Multiplatform root modules). Constraints are not dependencies.
            val below = mutableMapOf<ResolvedComponentResult, Set<String>>()
            fun shippedBelow(c: ResolvedComponentResult): Set<String> = below[c] ?: run {
                below[c] = emptySet() // cycles
                c.dependencies.filterIsInstance<ResolvedDependencyResult>().filterNot { it.isConstraint }.map { it.selected }
                    .flatMap { s -> s.id.displayName.let { if (it in shipped) setOf(it) else shippedBelow(s) } }
                    .toSortedSet().also { below[c] = it }
            }
            val root = classpath.resolutionResult.root
            shippedBelow(root).forEach { println("dep root $it") }
            classpath.resolutionResult.allComponents.filter { it.id.displayName in shipped }
                .sortedBy { it.id.displayName }
                .forEach { c -> (shippedBelow(c) - c.id.displayName).forEach { println("dep ${c.id.displayName} $it") } }

            // POMs carry the licences, sometimes only in a parent POM.
            val seen = shipped.keys.toMutableSet()
            var todo = shipped.values.map { Triple(it.group, it.module, it.version) }
            while (todo.isNotEmpty()) {
                val next = mutableListOf<Triple<String, String, String>>()
                val query = dependencies.createArtifactResolutionQuery()
                todo.forEach { (g, m, v) -> query.forModule(g, m, v) }
                query.withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
                    .resolvedComponents.sortedBy { it.id.displayName }.forEach { c ->
                        c.getArtifacts(MavenPomArtifact::class.java).forEach { a ->
                            val pom = (a as ResolvedArtifactResult).file
                            println("pom ${c.id.displayName} $pom")
                            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
                            val parent = doc.documentElement.childNodes.let { n -> (0 until n.length).map(n::item) }
                                .filterIsInstance<Element>().firstOrNull { it.tagName == "parent" } ?: return@forEach
                            fun text(tag: String) = parent.getElementsByTagName(tag).item(0).textContent.trim()
                            val id = Triple(text("groupId"), text("artifactId"), text("version"))
                            if (seen.add(id.toList().joinToString(":"))) next += id
                        }
                    }
                todo = next
            }
        }
    }
}
