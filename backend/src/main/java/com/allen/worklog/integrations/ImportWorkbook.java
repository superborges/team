package com.allen.worklog.integrations;

import com.allen.worklog.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbookType;
import static com.allen.worklog.integrations.ImportModels.*;

public final class ImportWorkbook {
    private ImportWorkbook() {}
    public static final int MAX_BYTES=2*1024*1024;
    public static byte[] errors(Batch batch) {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("错误行");var columns=new ArrayList<>(Dataset.parse(batch.dataset()).columns);
            columns.add("原行号");columns.add("失败原因");var header=sheet.createRow(0);
            for(int i=0;i<columns.size();i++)header.createCell(i).setCellValue(columns.get(i));
            int index=1;for(var row:batch.rows())if(row.error()!=null) {
                var excel=sheet.createRow(index++);int c=0;
                for(String column:Dataset.parse(batch.dataset()).columns)excel.createCell(c++).setCellValue(row.data().getOrDefault(column,""));
                excel.createCell(c++).setCellValue(Integer.toString(row.rowNumber()));excel.createCell(c).setCellValue(row.error());
            }
            sheet.createFreezePane(0,1);workbook.write(out);return out.toByteArray();
        } catch(IOException e) {throw new IllegalStateException("Cannot create error workbook",e);}
    }
    private static final Set<String> EXACT_COLUMNS=Set.of("code","departmentCode","approverEmployeeNo","sourceKey","sourceVersion","employeeNo");
    public static byte[] template(Dataset dataset) {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet(dataset.name());
            var text=workbook.createCellStyle();text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            var header=sheet.createRow(0);
            for(int i=0;i<dataset.columns.size();i++) {
                header.createCell(i).setCellValue(dataset.columns.get(i));
                sheet.setDefaultColumnStyle(i,text);sheet.setColumnWidth(i,24*256);
            }
            sheet.createFreezePane(0,1);workbook.write(out);return out.toByteArray();
        } catch(IOException e) { throw new IllegalStateException("Cannot generate XLSX template",e); }
    }
    static List<ParsedRow> read(Dataset dataset,byte[] bytes) {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw new ApiException(413,"IMPORT_SIZE","文件必须非空且不超过 2 MiB");
        try {
            // Bound decompressed content before constructing an in-memory workbook.
            try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                long total=0;int parts=0;byte[] buffer=new byte[8192];
                while(zip.getNextEntry()!=null) {
                    if(++parts>1000)throw invalid("XLSX 包含过多内容部件");
                    int count;while((count=zip.read(buffer))!=-1)if((total+=count)>16L*1024*1024)throw invalid("XLSX 解压后超过 16 MiB");
                }
                if(parts==0)throw invalid("请使用下载的 XLSX 模板");
            }
            try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                if(workbook.getWorkbookType()!=XSSFWorkbookType.XLSX || workbook.getNumberOfSheets()!=1 || !workbook.getExternalLinksTable().isEmpty())
                    throw invalid("只支持单工作表 XLSX，不接受宏或外部链接");
                for(var part:workbook.getPackage().getParts())if(part.getPartName().getName().toLowerCase().contains("vbaproject"))throw invalid("不接受包含宏的工作簿");
                var sheet=workbook.getSheetAt(0);var header=sheet.getRow(0);
                if(header==null||header.getLastCellNum()!=dataset.columns.size())throw invalid("列头数量与固定模板不符");
                for(int i=0;i<dataset.columns.size();i++) {
                    var cell=header.getCell(i);
                    if(cell==null||cell.getCellType()!=CellType.STRING||!dataset.columns.get(i).equals(cell.getStringCellValue()))throw invalid("列头名称或顺序与固定模板不符");
                }
                List<ParsedRow> rows=new ArrayList<>();
                for(var row:sheet) {
                    if(row.getRowNum()==0)continue;
                    boolean blank=true;for(var cell:row)if(cell.getCellType()!=CellType.BLANK && !(cell.getCellType()==CellType.STRING&&cell.getStringCellValue().isBlank()))blank=false;
                    if(blank)continue;
                    if(rows.size()==500)throw invalid("最多导入 500 条非空数据行");
                    var data=new LinkedHashMap<String,String>();String error=null;
                    for(int i=0;i<dataset.columns.size();i++) {
                        var cell=row.getCell(i);String value="";
                        if(cell!=null&&cell.getCellType()!=CellType.BLANK) {
                            if(cell.getCellType()!=CellType.STRING) {
                                error="所有数据单元格必须为文本，不接受公式、数字或日期单元格；请按模板文本列填写";
                                value=cell.getCellType()==CellType.FORMULA?"="+cell.getCellFormula():cell.toString();
                            } else {
                                value=cell.getStringCellValue();
                                if(EXACT_COLUMNS.contains(dataset.columns.get(i))) {
                                    if(!value.equals(value.strip()))error="编码、工号与来源版本不能带首尾空白；原值已保留，请核实后修正文件";
                                } else value=value.strip();
                            }
                        }
                        data.put(dataset.columns.get(i),value);
                    }
                    for(var cell:row)if(cell.getColumnIndex()>=dataset.columns.size()&&cell.getCellType()!=CellType.BLANK)error="数据行包含模板以外的列";
                    rows.add(new ParsedRow(row.getRowNum()+1,data,error));
                }
                if(rows.isEmpty())throw invalid("模板中没有可预览的数据行");
                return rows;
            }
        } catch(ApiException e) { throw e; }
        catch(Exception e) { throw invalid("无法读取 XLSX 文件，请使用固定模板并检查文件是否损坏"); }
    }
    private static ApiException invalid(String message) { return new ApiException(422,"INVALID_WORKBOOK",message); }
}
