/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 ******************************************************************************/
package org.apache.sling.scripting.sightly.impl.engine;

import org.apache.commons.io.IOUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.classloader.ClassLoaderWriter;
import org.apache.sling.commons.compiler.*;
import org.apache.sling.scripting.api.resource.ScriptingResourceResolverProvider;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.impl.engine.compiled.SourceIdentifier;
import org.apache.sling.scripting.sightly.impl.utils.ScriptUtils;
import org.apache.sling.scripting.sightly.render.RenderContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * The {@code SightlyJavaCompiler} allows for simple instantiation of arbitrary classes that are either stored in the repository
 * or in regular OSGi bundles. It also compiles Java sources on-the-fly and can discover class' source files based on
 * {@link Resource}s (typically Sling components). It supports Sling Resource type inheritance.
 */
@Component(
        service = SightlyJavaCompilerService.class
)
public class SightlyJavaCompilerService {

    public static final Pattern IMPORT_DECL_PATTERN = Pattern.compile("^\\s*import\\s+([\\w\\.]+);.*");
    private static final Logger LOG = LoggerFactory.getLogger(SightlyJavaCompilerService.class);
    private static final Pattern PACKAGE_DECL_PATTERN = Pattern.compile("(\\s*)package\\s+([a-zA-Z_$][a-zA-Z\\d_$]*\\.?)+;");
    @Reference
    private ClassLoaderWriter classLoaderWriter = null;

    @Reference
    private JavaCompiler javaCompiler = null;

    @Reference
    private ResourceBackedPojoChangeMonitor resourceBackedPojoChangeMonitor = null;

    @Reference
    private SightlyEngineConfiguration sightlyEngineConfiguration = null;

    @Reference
    private ScriptingResourceResolverProvider scriptingResourceResolverProvider = null;

    private Options options;

    /**
     * This method returns an Object instance based on a {@link Resource}-backed class that is either found through regular classloading
     * mechanisms or on-the-fly compilation. In case the requested class does not denote a fully qualified class name, this service will
     * try to find the class through Sling's resource resolution mechanism and compile the class on-the-fly if required.
     *
     * @param renderContext the render context
     * @param className     name of class to use for object instantiation
     * @return object instance of the requested class or {@code null} if the specified class is not backed by a {@link Resource}
     */
    public Object getResourceBackedUseObject(RenderContext renderContext, String className) {
        LOG.debug("Attempting to load class {}.", className);
        return getResourceBackedUseObject(renderContext, className, false);
    }

    /**
     * This method returns an Object instance based on a {@link Resource}-backed class that is either found through regular classloading
     * mechanisms or on-the-fly compilation. In case the requested class does not denote a fully qualified class name, this service will
     * try to find the class through Sling's resource resolution mechanism and compile the class on-the-fly if required.
     *
     * @param renderContext      the render context
     * @param className          name of class to use for object instantiation
     * @param ignoreDependencies whether or not the dependencies of the file should be ignored during compilation
     * @return object instance of the requested class or {@code null} if the specified class is not backed by a {@link Resource}
     * @author <a href="mailto:Stephan.Pirnbaum@googlemail.com">Stephan Pirnbaum</a>
     */
    private Object getResourceBackedUseObject(RenderContext renderContext, String className, boolean ignoreDependencies) {
        LOG.debug("Attempting to load class {}.", className);
        try {
            if (className.contains(".")) {
                Resource pojoResource =
                        SourceIdentifier.getPOJOFromFQCN(scriptingResourceResolverProvider.getRequestScopedResourceResolver()
                                , null, className);
                if (pojoResource != null) {
                    getUseObjectAndRecompileIfNeeded(pojoResource, ignoreDependencies);
                }
            } else {
                Resource pojoResource = ScriptUtils.resolveScript(
                        scriptingResourceResolverProvider.getRequestScopedResourceResolver(),
                        renderContext,
                        className + ".java"
                );
                if (pojoResource != null) {
                    getUseObjectAndRecompileIfNeeded(pojoResource, ignoreDependencies);
                }
            }
        } catch (Exception e) {
            throw new SightlyException("Cannot obtain an instance for class " + className + ".", e);
        }
        return null;
    }

    /**
     * Compiles a class using the passed fully qualified class name and its source code.
     *
     * @param sourceIdentifier the source identifier
     * @param sourceCode       the source code from which to generate the class
     * @return object instance of the class to compile
     */
    public Object compileSource(SourceIdentifier sourceIdentifier, String sourceCode) {
        Map<SourceIdentifier, String> sources = new HashMap<>();
        sources.put(sourceIdentifier, sourceCode);
        return compileSources(sources, sourceIdentifier);
    }

    /**
     * Compiles all specified sources and and loads the given one.
     *
     * @param sources     The sources to compile, a mapping from an identifier to its source code.
     * @param classToLoad The class to load.
     * @return An instance of the compiled class to load.
     * @author <a href="mailto:Stephan.Pirnbaum@googlemail.com">Stephan Pirnbaum</a>
     */
    private Object compileSources(Map<SourceIdentifier, String> sources, SourceIdentifier classToLoad) {
        try {
            List<CompilationUnit> compilationUnits = new ArrayList<>(sources.entrySet().size());
            for (Map.Entry<SourceIdentifier, String> source : sources.entrySet()) {
                if (sightlyEngineConfiguration.keepGenerated()) {
                    String path = "/" + source.getKey().getFullyQualifiedClassName().replaceAll("\\.", "/") + ".java";
                    OutputStream os = classLoaderWriter.getOutputStream(path);
                    IOUtils.write(source.getValue(), os, "UTF-8");
                    IOUtils.closeQuietly(os);
                }
                compilationUnits.add(
                        new SightlyCompilationUnit(
                                completeJavaSourceFileWithPackageDeclaration(source.getValue(), source.getKey()),
                                source.getKey().getFullyQualifiedClassName()
                        )
                );
            }

            long start = System.currentTimeMillis();
            CompilationResult compilationResult = javaCompiler.compile(
                    compilationUnits.toArray(new CompilationUnit[compilationUnits.size()]),
                    options);
            long end = System.currentTimeMillis();
            List<CompilerMessage> errors = compilationResult.getErrors();
            if (errors != null && errors.size() > 0) {
                throw new SightlyException(createErrorMsg(errors));
            }
            if (compilationResult.didCompile()) {
                LOG.debug("Classes {} were compiled in {}ms.",
                        Arrays.toString(sources.keySet().toArray()),
                        end - start);
            }
            /*
             * the class loader might have become dirty, so let the {@link ClassLoaderWriter} decide which class loader to return
             */
            return classLoaderWriter.getClassLoader().loadClass(classToLoad.getFullyQualifiedClassName()).newInstance();
        } catch (Exception e) {
            throw new SightlyException(e);
        }
    }

    /**
     * Completes a source file with a package declaration if no one is present in the java file. Therefor, it uses the
     * fully qualified java class name.
     *
     * @param sourceCode The source to complete with a package declaration.
     * @param sourceIdentifier The source identifier.
     * @return The source file completed with a package declaration.
     * @author <a href="mailto:Stephan.Pirnbaum@googlemail.com">Stephan Pirnbaum</a>
     */
    private String completeJavaSourceFileWithPackageDeclaration(String sourceCode, SourceIdentifier sourceIdentifier) {
        String[] sourceCodeLines = sourceCode.split("\\r\\n|[\\n\\x0B\\x0C\\r\\u0085\\u2028\\u2029]");
        boolean foundPackageDeclaration = false;
        for (String line : sourceCodeLines) {
            Matcher matcher = PACKAGE_DECL_PATTERN.matcher(line);
            if (matcher.matches()) {
                /**
                 * This matching might return false positives like:
                 * // package a.b.c;
                 *
                 * where from a syntactic point of view the source code doesn't have a package declaration and the expectancy is that our
                 * SightlyJavaCompilerService will add one.
                 */
                foundPackageDeclaration = true;
                break;
            }
        }

        if (!foundPackageDeclaration) {
            sourceCode = "package " + sourceIdentifier.getPackageName() + ";\n" + sourceCode;
        }
        return sourceCode;
    }

    Object getUseObjectAndRecompileIfNeeded(Resource pojoResource, boolean ignoreDependencies)
            throws IOException, InstantiationException, IllegalAccessException, ClassNotFoundException {
        SourceIdentifier sourceIdentifier = new SourceIdentifier(sightlyEngineConfiguration, pojoResource.getPath());
        long sourceLastModifiedDateFromCache =
                resourceBackedPojoChangeMonitor.getLastModifiedDateForJavaUseObject(pojoResource.getPath());
        long classLastModifiedDate = classLoaderWriter.getLastModified("/" + sourceIdentifier.getFullyQualifiedClassName()
                .replaceAll("\\.", "/") + ".class");
        if (sourceLastModifiedDateFromCache == 0) {
            // first access; let's check the real last modified date of the source
            long sourceLastModifiedDate = pojoResource.getResourceMetadata().getModificationTime();
            resourceBackedPojoChangeMonitor.recordLastModifiedTimestamp(pojoResource.getPath(), sourceLastModifiedDate);
            if (classLastModifiedDate < 0 || sourceLastModifiedDate > classLastModifiedDate) {
                if (ignoreDependencies) {
                    return compileSource(sourceIdentifier, IOUtils.toString(pojoResource.adaptTo(InputStream.class), "UTF-8"));
                } else {
                    return compileSources(getDependenciesOfClass(sourceIdentifier.getFullyQualifiedClassName()), sourceIdentifier);
                }
            } else {
                return classLoaderWriter.getClassLoader().loadClass(sourceIdentifier.getFullyQualifiedClassName()).newInstance();
            }
        } else {
            if (sourceLastModifiedDateFromCache > classLastModifiedDate) {
                if (ignoreDependencies) {
                    return compileSource(sourceIdentifier, IOUtils.toString(pojoResource.adaptTo(InputStream.class), "UTF-8"));
                } else {
                    return compileSources(getDependenciesOfClass(sourceIdentifier.getFullyQualifiedClassName()), sourceIdentifier);
                }
            } else {
                return classLoaderWriter.getClassLoader().loadClass(sourceIdentifier.getFullyQualifiedClassName()).newInstance();
            }
        }
    }

    /**
     * Returns all dependencies of a specified class which reside under the base package 'apps' as a mapping from the
     * identifier to its source code including itself.
     * @param className The class from which the dependencies should be resolved
     * @return A mapping from identifier to ource code
     */
    private Map<SourceIdentifier, String> getDependenciesOfClass(String className) {
        ResourceResolver resolver = this.scriptingResourceResolverProvider.getRequestScopedResourceResolver();
        Resource pojoResource = SourceIdentifier.getPOJOFromFQCN(
                resolver,
                sightlyEngineConfiguration.getBundleSymbolicName(),
                className);
        Map<SourceIdentifier, String> sources = new HashMap<SourceIdentifier, String>();
        try {
            String sourceCode = IOUtils.toString(pojoResource.adaptTo(InputStream.class), "UTF-8");
            Set<String> dependencies = getDependencies(className, resolver);
            SourceIdentifier sourceIdentifier = new SourceIdentifier(
                    sightlyEngineConfiguration,
                    pojoResource.getPath());
            sources.put(sourceIdentifier, sourceCode);
            for (String dependency : dependencies) {
                Resource dependencyPojoResource = SourceIdentifier.getPOJOFromFQCN(
                        resolver,
                        sightlyEngineConfiguration.getBundleSymbolicName(),
                        dependency);
                try {
                    String dependencySourceCode = IOUtils.toString(dependencyPojoResource.adaptTo(InputStream.class), "UTF-8");
                    SourceIdentifier dependencySourceIdentifier = new SourceIdentifier(
                            sightlyEngineConfiguration,
                            dependencyPojoResource.getPath());
                    sources.put(
                            dependencySourceIdentifier,
                            dependencySourceCode
                    );
                } catch (IOException e) {
                    LOG.error("Failed to retrieve source code from class: " + className, e);
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to retrieve source code from class: " + className, e);
        }
        return sources;
    }

    /**
     * Gets all dependencies from a specified java class including imports and dependencies from the same package recursive.
     * Only classes in the "apps" base package are considered.
     *
     * @param fqcn     The class as fully qualified java class name to get the dependencies from.
     * @param resolver The resource resolver to use.
     * @return All dependencies of the class.
     * @author <a href="mailto:Stephan.Pirnbaum@t-systems.com">Stephan Pirnbaum</a>
     * @see SightlyJavaCompilerService#extractDependencies(String)
     * @see SightlyJavaCompilerService#getLocalJavaDependencies(String, Resource)
     */
    private Set<String> getDependencies(String fqcn, ResourceResolver resolver) {
        String basePackage = "apps";
        Set<String> dependencies = new TreeSet<String>();
        Queue<String> dependencyQueue = new LinkedList<String>();
        dependencyQueue.add(fqcn);
        while (dependencyQueue.peek() != null) {
            String currentDependency = dependencyQueue.poll();
            Resource resource = SourceIdentifier.getPOJOFromFQCN(
                    resolver,
                    sightlyEngineConfiguration.getBundleSymbolicName(),
                    currentDependency);
            try {
                dependencies.add(currentDependency);
                String sourceCode = IOUtils.toString(resource.adaptTo(InputStream.class), "UTF-8");
                Set<String> newDependencies = extractDependencies(sourceCode);
                newDependencies.addAll(getLocalJavaDependencies(currentDependency, resource));
                for (String dependency : newDependencies) {
                    if (dependency.startsWith(basePackage) && !dependencies.contains(dependency) && !dependencyQueue.contains(dependency)) {
                        dependencyQueue.offer(dependency);
                    }
                }
            } catch (IOException e) {
                throw new SightlyException(String.format(
                        "Unable to compile class %s from %s.",
                        currentDependency,
                        resource.getPath()), e);
            }

        }
        // remove class which was specified to extract the dependencies from
        dependencies.remove(fqcn);
        return dependencies;
    }

    /**
     * Returns all java classes inside the same package as the class specified by the resource. Therefor, it checks if the
     * java classes located in the same package are referenced in the one specified and if so, adds it to the result set.
     * This might return false positives since the check is using simple matching of the class name of the possible
     * dependency.
     *
     * @param fqcn     The class to get the local dependencies from as fully qualified java class name.
     * @param resource The resource of the java class.
     * @return All local dependencies.
     * @author <a href="mailto:Stephan.Pirnbaum@t-systems.com">Stephan Pirnbaum</a>
     */
    private Set<String> getLocalJavaDependencies(String fqcn, Resource resource) {
        Set<String> dependencies = new TreeSet<String>();
        try {
            String sourceCode = IOUtils.toString(resource.adaptTo(InputStream.class), "UTF-8");
            String resourcePath;
            Resource parent = resource.getParent();
            if (parent != null) {
                /*
                 * iterate over every file contained in the same package/directory as the one specified by the resource parameter
                 */
                for (Resource childResource : parent.getChildren()) {
                    resourcePath = childResource.getPath();
                    /*
                     * reduce number of possible files to java files
                     */
                    if (childResource.isResourceType("nt:file") && resourcePath.endsWith(".java")) {
                        String possibleDependency;
                        if (resourcePath.startsWith("/")) {
                            possibleDependency =
                                    resourcePath.replaceFirst("/", "")
                                            .replaceAll("/", "\\.")
                                            .replaceAll("-", "_")
                                            .substring(0, resourcePath.length() - ".java".length() - 1);
                        } else {
                            possibleDependency =
                                    resourcePath
                                            .replaceAll("/", "\\.")
                                            .replaceAll("-", "_")
                                            .substring(0, resourcePath.length() - ".java".length() - 1);
                        }
                        // Check if the dependency is referenced in the source code
                        String className;
                        if (possibleDependency.contains(".")) {
                            className = possibleDependency.substring(possibleDependency.lastIndexOf(".") + 1);
                        } else {
                            className = possibleDependency;
                        }
                        if (sourceCode.contains(className)) {
                            // check that no external dependency of same class name is referenced
                            boolean foundExternalDep = false;
                            for (String externalDependency : extractDependencies(sourceCode)) {
                                if (externalDependency.contains(className) && !externalDependency.equals(possibleDependency)) {
                                    foundExternalDep = true;
                                }
                            }
                            if (foundExternalDep) {
                                // if this is the case, than the local dependency must be referenced using the fully qualified name
                                if (sourceCode.contains(possibleDependency)) {
                                    dependencies.add(possibleDependency);
                                }
                            } else {
                                dependencies.add(possibleDependency);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new SightlyException(String.format("Unable to compile class %s from %s.", fqcn, resource.getPath()), e);
        }
        return dependencies;
    }

    /**
     * Extracts all import statements from a source code file and returns all imported classes represented by their fully
     * qualified name. Package imports are not supported.
     *
     * @param sourceCode The source code to check
     * @return A set of all imported classes
     * @author <a href="mailto:Stephan.Pirnbaum@t-systems.com">Stephan Pirnbaum</a>
     */
    private Set<String> extractDependencies(String sourceCode) {
        String[] sourceCodeLines = sourceCode.split("\\r\\n|[\\n\\x0B\\x0C\\r\\u0085\\u2028\\u2029]");
        Set<String> dependencies = new TreeSet<String>();
        for (String sourceCodeLine : sourceCodeLines) {
            Matcher matcher = IMPORT_DECL_PATTERN.matcher(sourceCodeLine);
            if (matcher.matches()) {
                /*
                 * found an import statement in the source code file
                 */
                String dependencyFqcn = matcher.group(1);
                dependencies.add(dependencyFqcn);
            }
        }
        return dependencies;
    }

    @Activate
    protected void activate() {
        LOG.info("Activating {}", getClass().getName());

        String version = System.getProperty("java.specification.version");
        options = new Options();
        options.put(Options.KEY_GENERATE_DEBUG_INFO, true);
        options.put(Options.KEY_SOURCE_VERSION, version);
        options.put(Options.KEY_TARGET_VERSION, version);
        options.put(Options.KEY_CLASS_LOADER_WRITER, classLoaderWriter);
        options.put(Options.KEY_FORCE_COMPILATION, true);
    }

    //---------------------------------- private -----------------------------------
    private String createErrorMsg(List<CompilerMessage> errors) {
        final StringBuilder buffer = new StringBuilder();
        buffer.append("Compilation errors in ");
        buffer.append(errors.get(0).getFile());
        buffer.append(":");
        StringBuilder errorsBuffer = new StringBuilder();
        boolean duplicateVariable = false;
        for (final CompilerMessage e : errors) {
            if (!duplicateVariable) {
                if (e.getMessage().contains("Duplicate local variable")) {
                    duplicateVariable = true;
                    buffer.append(" Maybe you defined more than one identical block elements without defining a different variable for "
                            + "each one?");
                }
            }
            errorsBuffer.append("\nLine ");
            errorsBuffer.append(e.getLine());
            errorsBuffer.append(", column ");
            errorsBuffer.append(e.getColumn());
            errorsBuffer.append(" : ");
            errorsBuffer.append(e.getMessage());
        }
        buffer.append(errorsBuffer);
        return buffer.toString();
    }

    private static class SightlyCompilationUnit implements CompilationUnit {

        private String fqcn;
        private String sourceCode;

        SightlyCompilationUnit(String sourceCode, String fqcn) throws Exception {
            this.sourceCode = sourceCode;
            this.fqcn = fqcn;
        }

        @Override
        public Reader getSource() throws IOException {
            return new InputStreamReader(IOUtils.toInputStream(sourceCode, "UTF-8"), "UTF-8");
        }

        @Override
        public String getMainClassName() {
            return fqcn;
        }

        @Override
        public long getLastModified() {
            return System.currentTimeMillis();
        }
    }
}
