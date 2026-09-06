package com.allen.worklog.integrations;

import com.allen.worklog.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static com.allen.worklog.integrations.ImportModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportWorkbookTest {
    static byte[] workbook(Dataset dataset,List<List<String>> rows) throws Exception {
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(ImportWorkbook.template(dataset)));var out=new ByteArrayOutputStream()) {
            for(int i=0;i<rows.size();i++) {
                var row=workbook.getSheetAt(0).createRow(i+1);
                for(int j=0;j<rows.get(i).size();j++)row.createCell(j).setCellValue(rows.get(i).get(j));
            }
            workbook.write(out);return out.toByteArray();
        }
    }
    @Test void realPoiRoundTripPreservesLeadingZerosAndOpaqueSourceVersions() throws Exception {
        byte[] bytes=workbook(Dataset.LEAVE,List.of(List.of("REQ-001","0001","00123","2026-09-07","240","540","780","APPROVED")));
        var row=ImportWorkbook.read(Dataset.LEAVE,bytes).getFirst();
        assertNull(row.parseError());assertEquals("00123",row.data().get("employeeNo"));assertEquals("0001",row.data().get("sourceVersion"));
    }
    @Test void formulasAndNumericIdentityCellsAreRejectedWithoutEvaluation() throws Exception {
        byte[] initial=workbook(Dataset.LEAVE,List.of(List.of("REQ-001","1","00123","2026-09-07","240","540","780","APPROVED")));
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(initial));var out=new ByteArrayOutputStream()) {
            workbook.getSheetAt(0).getRow(1).getCell(2).setCellValue(123);
            workbook.getSheetAt(0).getRow(1).getCell(4).setCellFormula("120+120");
            workbook.write(out);
            var row=ImportWorkbook.read(Dataset.LEAVE,out.toByteArray()).getFirst();
            assertNotNull(row.parseError());assertEquals("=120+120",row.data().get("leaveMinutes"));
        }
    }
    @Test void rowLimitAndWrongTemplateAreRejected() throws Exception {
        var rows=java.util.Collections.nCopies(501,List.of("JUNIOR","800","2027-01-01",""));
        assertThrows(ApiException.class,()->ImportWorkbook.read(Dataset.RATE,workbook(Dataset.RATE,rows)));
        byte[] rate=workbook(Dataset.RATE,List.of(List.of("JUNIOR","800","2027-01-01","")));
        assertThrows(ApiException.class,()->ImportWorkbook.read(Dataset.PROJECT,rate));
    }
    @Test void opaqueIdentifiersWithWhitespaceAreRejectedAndRetainedExactly() throws Exception {
        byte[] bytes=workbook(Dataset.LEAVE,List.of(List.of(" REQ-001","0001 "," 00123","2026-09-07","240","540","780","APPROVED")));
        var row=ImportWorkbook.read(Dataset.LEAVE,bytes).getFirst();
        assertNotNull(row.parseError());assertEquals(" REQ-001",row.data().get("sourceKey"));
        assertEquals("0001 ",row.data().get("sourceVersion"));assertEquals(" 00123",row.data().get("employeeNo"));
    }
}
