package com.pedjak.gradle.plugins.dockerizedtest;

import java.io.File;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.ImmutableSet;

import org.gradle.api.file.FileTree;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.tasks.testing.TestClassProcessor;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.internal.tasks.testing.WorkerTestClassProcessorFactory;
import org.gradle.api.internal.tasks.testing.detection.DefaultTestClassScanner;
import org.gradle.api.internal.tasks.testing.detection.TestFrameworkDetector;
import org.gradle.api.internal.tasks.testing.processors.MaxNParallelTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.RestartEveryNTestClassProcessor;
import org.gradle.api.internal.tasks.testing.processors.TestMainAction;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.Factory;
import org.gradle.internal.time.TrueTimeProvider;
import org.gradle.internal.actor.ActorFactory;
import org.gradle.internal.progress.BuildOperationExecutor;
import org.gradle.process.internal.worker.WorkerProcessFactory;
import org.gradle.internal.operations.BuildOperationWorkerRegistry;

public class TestExecuter implements org.gradle.api.internal.tasks.testing.detection.TestExecuter
{
    private final WorkerProcessFactory workerFactory;
    private final ActorFactory actorFactory;
    private final ModuleRegistry moduleRegistry;
    private final BuildOperationWorkerRegistry buildOperationWorkerRegistry;
    private final BuildOperationExecutor buildOperationExecutor;

    public TestExecuter(WorkerProcessFactory workerFactory, ActorFactory actorFactory, ModuleRegistry moduleRegistry, BuildOperationWorkerRegistry buildOperationWorkerRegistry, BuildOperationExecutor buildOperationExecutor) {
        this.workerFactory = workerFactory;
        this.actorFactory = actorFactory;
        this.moduleRegistry = moduleRegistry;
        this.buildOperationWorkerRegistry = buildOperationWorkerRegistry;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public void execute(final Test testTask, TestResultProcessor testResultProcessor) {
        final TestFramework testFramework = testTask.getTestFramework();
        final WorkerTestClassProcessorFactory testInstanceFactory = testFramework.getProcessorFactory();
        BuildOperationWorkerRegistry.Completion currentOperationCompletition = null;
        final Set<File> classpath = ImmutableSet.copyOf(testTask.getClasspath());
        final Factory<TestClassProcessor> forkingProcessorFactory = new Factory<TestClassProcessor>() {
            public TestClassProcessor create() {
                return new ForkingTestClassProcessor(workerFactory, testInstanceFactory, testTask,
                        classpath, testFramework.getWorkerConfigurationAction(), moduleRegistry);
            }
        };
        Factory<TestClassProcessor> reforkingProcessorFactory = new Factory<TestClassProcessor>() {
            public TestClassProcessor create() {
                return new RestartEveryNTestClassProcessor(forkingProcessorFactory, testTask.getForkEvery());
            }
        };

        TestClassProcessor processor = new MaxNParallelTestClassProcessor(testTask.getMaxParallelForks(),
                reforkingProcessorFactory, actorFactory);

        final FileTree testClassFiles = testTask.getCandidateClassFiles();

        Runnable detector;
        if (testTask.isScanForTestClasses()) {
            TestFrameworkDetector testFrameworkDetector = testTask.getTestFramework().getDetector();
            testFrameworkDetector.setTestClassesDirectory(testTask.getTestClassesDir());
            testFrameworkDetector.setTestClasspath(classpath);
            detector = new DefaultTestClassScanner(testClassFiles, testFrameworkDetector, processor);
        } else {
            detector = new DefaultTestClassScanner(testClassFiles, null, processor);
        }

        Object testTaskOperationId;

        try
        {
            testTaskOperationId = buildOperationExecutor.getCurrentOperation().getId();
        } catch (Exception e) {
            testTaskOperationId = UUID.randomUUID();
        }

        new TestMainAction(detector, processor, testResultProcessor, new TrueTimeProvider(), testTaskOperationId, testTask.getPath(), "Gradle Test Run " + testTask.getIdentityPath()).run();
    }
}
