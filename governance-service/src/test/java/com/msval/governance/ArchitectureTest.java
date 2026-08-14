package com.msval.governance;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * TASK-005 layering gate (Stage 2, noted in GovernanceApplication): the decision core
 * stays pure — engine and cdm must not depend on the service-side packages.
 */
class ArchitectureTest {

    @Test
    void engineAndCdmStayPure() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.msval.governance");

        ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage("com.msval.governance.engine..", "com.msval.governance.cdm..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.msval.governance.api..",
                        "com.msval.governance.persist..",
                        "com.msval.governance.gateway..",
                        "com.msval.governance.registry..",
                        "com.msval.governance.orchestrate..",
                        "com.msval.governance.findings..")
                .because("DD-009: the engine is pure — no I/O, no clocks, no service wiring")
                .allowEmptyShould(true)
                .check(classes);
    }
}
