package com.patipp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable architecture.
 *
 * <p>The rules in {@code docs/ARCHITECTURE.md} are worth nothing if the only thing enforcing
 * them is somebody remembering to read the document. These tests are the enforcement; a
 * violation fails the build in the same way a broken feature does.
 *
 * <p>The two rules about {@code adaptive} and {@code scheduling} are written now, before
 * either package exists, and pass vacuously until Phase 5 creates them. That is on purpose:
 * the constraint is in place on the day the first line of the engine is written, rather than
 * being retrofitted after the engine has already grown a dependency on JPA.
 */
class ModuleBoundaryTest {

    private static final String BASE = "com.patipp";
    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("common must not depend on any feature module")
    void commonIsIndependent() {
        // Every module uses common. If common used a feature module back, the dependency
        // graph would have a cycle at the one point everything relies on.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..auth..", "..users..", "..preparations..", "..curriculum..",
                        "..questions..", "..attempts..", "..sessions..", "..interviews..",
                        "..adaptive..", "..scheduling..", "..analytics..",
                        "..recommendations..", "..materials..", "..imports..", "..ai..", "..sync..")
                .because("common is the shared foundation and must sit below every feature module");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("the adaptive engine must stay free of Spring, JPA and the web layer")
    void adaptiveEngineIsPure() {
        // The selection algorithm will be rewritten several times. It must be testable
        // against a hand-built learner model in milliseconds, with no container and no
        // database, or those rewrites will never be verified properly.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..adaptive..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "jakarta.servlet..")
                .because("the adaptive engine is pure domain logic: plain records in, plain records out")
                // adaptive does not exist until Phase 5. The rule is in place from today so the
                // constraint is active on the day the first line of the engine is written.
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("the readiness model must stay free of Spring, JPA and the web layer")
    void readinessModelIsPure() {
        // Same reason as the other two engines. A readiness score is a weighted combination of
        // six judgements, and the only way to know whether a change to those weights is an
        // improvement is to run it against many hand-built learners in milliseconds.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..analytics.model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "jakarta.servlet..")
                .because("the readiness model is pure domain logic: plain records in, plain "
                        + "records out, so a change to the weights can be evaluated without a "
                        + "database")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("the scheduling engine must stay free of Spring, JPA and the web layer")
    void schedulingEngineIsPure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..scheduling..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "jakarta.servlet..")
                .because("the spaced-repetition scheduler must be testable by advancing a clock, "
                        + "with no container and no database")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("domain classes must not depend on the web layer")
    void domainDoesNotKnowAboutHttp() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "jakarta.servlet..")
                .because("an entity that knows about HTTP cannot be reused outside a request");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("a module's internal package must not be reachable from another module")
    void internalPackagesAreModulePrivate() {
        // Each module publishes its api package. internal is where a module is free to
        // change its mind without breaking anybody.
        for (String module : new String[]{"auth", "preparations", "curriculum", "learning"}) {
            ArchRule rule = noClasses()
                    .that().resideOutsideOfPackage(BASE + "." + module + "..")
                    .should().dependOnClassesThat().resideInAPackage(BASE + "." + module + ".internal..")
                    .because(module + ".internal is private to the " + module
                            + " module; other modules must go through " + module + ".api");

            rule.check(productionClasses);
        }
    }

    @Test
    @DisplayName("controllers must not be reached from outside the web layer")
    void controllersAreEntryPointsOnly() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..api..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .because("a controller is an entry point, never a collaborator");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("no field injection")
    void noFieldInjection() {
        // Constructor injection makes dependencies visible and objects testable without a
        // container. Field injection hides both.
        ArchRule rule = fields()
                .should().notBeAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
                .because("dependencies belong in the constructor, where they are visible and final");

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("repositories live in domain packages")
    void repositoriesStayInDomain() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Repository")
                .should().resideInAPackage("..domain..")
                .because("persistence contracts belong beside the entities they load");

        rule.check(productionClasses);
    }
}
