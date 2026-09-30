"""Fail CI when required integration tests are missing, skipped, or broken."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


REQUIRED = {
    ("tacocloud-api", "tacos.design.TacoDesignRulesTest"),
    ("tacocloud-api", "tacos.pricing.OrderPricingServiceTest"),
    ("tacocloud-api", "tacos.workflow.OrderWorkflowMatrixTest"),
    ("tacocloud-api", "tacos.web.api.IngredientControllerTest"),
    ("tacocloud-api", "tacos.web.api.OrderApiControllerTest"),
    ("tacocloud-api", "tacos.web.api.OrderPutDeleteControllerTest"),
    ("tacocloud-api", "tacos.web.api.EmailOrderServiceTest"),
    ("tacocloud-api", "tacos.web.api.OrderFromEmailControllerTest"),
    ("tacocloud-api", "tacos.web.api.PaymentSecurityContractTest"),
    ("tacocloud-api", "tacos.outbox.OrderOutboxMongoTest"),
    ("tacocloud-api", "tacos.idempotency.OrderIdempotencyMongoTest"),
    ("tacocloud-api", "tacos.api.OpenApiContractTest"),
    ("tacocloud-security", "tacos.security.RegistrationServiceTest"),
    ("tacocloud-security", "tacos.security.SecurityAuthorizationTest"),
    ("tacocloud-kitchen", "tacos.kitchen.delivery.RabbitKitchenDeliveryTest"),
    ("tacocloud", "tacos.Tc36RuntimeContractTest"),
    ("tacocloud", "tacos.UiSecuritySmokeTest"),
    ("tacocloud-order-contract", "tacos.messaging.OrderEventContractTest"),
}

REQUIRED_CASES = {
    ("tacocloud-api", "tacos.web.api.IngredientControllerTest",
     "shouldEmitAndComplete"),
    ("tacocloud-api", "tacos.web.api.IngredientControllerTest",
     "shouldWaitForIngredientDeletePublisherBeforeNoContent"),
    ("tacocloud-api", "tacos.web.api.OrderApiControllerTest",
     "shouldPatchZipWithoutChangingState"),
    ("tacocloud-api", "tacos.web.api.OrderPutDeleteControllerTest",
     "shouldWaitForDeleteCompletionBeforeReturningNoContent"),
    ("tacocloud-api", "tacos.web.api.EmailOrderServiceTest",
     "shouldNotEmitOrderUntilIngredientLookupCompletes"),
    ("tacocloud-api", "tacos.web.api.OrderFromEmailControllerTest",
     "shouldSubscribeToColdConversionOnceAndSaveWithoutDirectPublishing"),
    ("tacocloud-security", "tacos.security.RegistrationServiceTest",
     "shouldSubscribeToSaveAndPersistOnlyTheAdaptiveHash"),
    ("tacocloud-security", "tacos.security.SecurityAuthorizationTest",
     "shouldDenyUnlistedRouteByDefault"),
    ("tacocloud-api", "tacos.web.api.PaymentSecurityContractTest",
     "shouldRemoveRawCardFieldsFromOrderDomain"),
    ("tacocloud-api", "tacos.outbox.OrderOutboxMongoTest",
     "shouldRetryBrokerFailureAfterRestartWithSameEventId"),
    ("tacocloud-kitchen", "tacos.kitchen.delivery.RabbitKitchenDeliveryTest",
     "shouldConsumePublishedEventTwiceButChangeKitchenStateOnce"),
    ("tacocloud-kitchen", "tacos.kitchen.delivery.RabbitKitchenDeliveryTest",
     "shouldDeadLetterExhaustedTransientFailure"),
    ("tacocloud-api", "tacos.idempotency.OrderIdempotencyMongoTest",
     "sequentialRetryKeepsOneOrderReservationAndOutboxEvent"),
    ("tacocloud-api", "tacos.api.OpenApiContractTest",
     "postOrderAndApiProblemMatchDocumentedStatusAndSchema"),
    ("tacocloud", "tacos.Tc36RuntimeContractTest",
     "userCannotReachAdminOrUndeclaredRoutes"),
    ("tacocloud", "tacos.Tc36RuntimeContractTest",
     "csrfBlocksWriteWithoutTokenAndAllowsValidatedControllerCall"),
    ("tacocloud", "tacos.Tc36RuntimeContractTest",
     "adminCreateAndDeleteChangeRealMongoState"),
}

# These pre-existing Selenium examples are disabled in the normal Maven run.
LEGACY_SKIPS = {
    ("tacocloud", "tacos.DesignAndOrderTacosBrowserTest",
     "tacos.DesignAndOrderTacosBrowserTest"),
    ("tacocloud", "tacos.DesignTacoControllerBrowserTest",
     "testDesignATacoPage"),
    ("tacocloud", "tacos.HomeControllerTest", "tacos.HomeControllerTest"),
    ("tacocloud-web", "tacos.DesignAndOrderTacosBrowserTest",
     "tacos.DesignAndOrderTacosBrowserTest"),
    ("tacocloud-web", "tacos.DesignTacoControllerBrowserTest",
     "tacos.DesignTacoControllerBrowserTest"),
    ("tacocloud-web", "tacos.HomeControllerTest", "tacos.HomeControllerTest"),
}


def verify(reactor):
    reports = sorted(reactor.glob("*/target/surefire-reports/TEST-*.xml"))
    problems = []
    seen = set()
    seen_cases = set()
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    if not reports:
        problems.append("No Surefire XML reports were produced")

    for report in reports:
        module = report.parents[2].name
        try:
            suite = ET.parse(report).getroot()
            counts = {key: int(suite.get(key, "0")) for key in totals}
        except (ET.ParseError, ValueError) as error:
            problems.append(f"Unreadable report {report}: {error}")
            continue
        for key in totals:
            totals[key] += counts[key]
        name = suite.get("name", "")
        identity = (module, name)
        seen.add(identity)
        if identity in REQUIRED and (counts["tests"] == 0 or counts["skipped"]):
            problems.append(f"Required suite did not run fully: {module}/{name}")
        if counts["failures"] or counts["errors"]:
            problems.append(f"Failed suite: {module}/{name}")
        for case in suite.findall("testcase"):
            case_identity = (module, case.get("classname", name),
                             case.get("name", ""))
            seen_cases.add(case_identity)
            if case.find("skipped") is not None and case_identity not in LEGACY_SKIPS:
                problems.append(
                    f"Unexpected skipped test: {module}/{name}#{case.get('name')}"
                )

    for module, name in sorted(REQUIRED - seen):
        problems.append(f"Required suite missing: {module}/{name}")
    for module, name, case in sorted(REQUIRED_CASES - seen_cases):
        problems.append(f"Required test missing: {module}/{name}#{case}")

    print("TC-36 Surefire gate:", ", ".join(
        f"{key}={value}" for key, value in totals.items()))
    if problems:
        for problem in problems:
            print("ERROR:", problem, file=sys.stderr)
        return 1
    print("All required suites ran, with no critical or unexpected skips")
    return 0


if __name__ == "__main__":
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("tacocloud")
    sys.exit(verify(root))
