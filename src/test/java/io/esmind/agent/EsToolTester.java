package io.esmind.agent;

/**
 * Quick integration test for EsTool against the test ES 6.5.4 instance.
 * Tests the three tools: listIndices, getMapping, executeQuery.
 */
public class EsToolTester {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "192.168.8.59";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9230;

        EsTool tool = new EsTool(host, port, "http", null, null);
        int version = tool.detectMajorVersion();
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  ES " + version + ".x Connected ✓");
        System.out.println("═══════════════════════════════════════════\n");

        // 1. listIndices
        System.out.println("▶ [Tool 1] es_list_indices");
        System.out.println("─────────────────────────────────────────");
        System.out.println(tool.listIndices());
        System.out.println();

        // 2. getMapping
        System.out.println("▶ [Tool 2] es_get_mapping");
        System.out.println("─────────────────────────────────────────");
        String mappingResult = tool.getMapping("xnk20231220_clinical_inhistory_0122113057");
        System.out.println(mappingResult.substring(0, Math.min(mappingResult.length(), 2000)));
        System.out.println();

        // 3. executeQuery - patient query
        System.out.println("▶ [Tool 3] es_execute_query — 按患者ID查询");
        System.out.println("─────────────────────────────────────────");
        String result = tool.executeQuery(
            "xnk20231220_clinical_inhistory_0122113057",
            "{\"match\": {\"patient.id\": \"000618062300\"}}",
            5
        );
        System.out.println(result);
        System.out.println();

        // 4. executeQuery - match_all
        System.out.println("▶ [Tool 3] es_execute_query — match_all (列出所有患者)");
        System.out.println("─────────────────────────────────────────");
        String allResult = tool.executeQuery(
            "xnk20231220_clinical_inhistory_0122113057",
            "{\"match_all\": {}}",
            10
        );
        System.out.println(allResult);

        tool.close();
    }
}
