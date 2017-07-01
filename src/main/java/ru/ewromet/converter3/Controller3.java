package ru.ewromet.converter3;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.xml.sax.SAXException;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.FileChooser;
import javafx.util.converter.DoubleStringConverter;
import javafx.util.converter.IntegerStringConverter;
import ru.ewromet.Controller;
import ru.ewromet.OrderRow;
import ru.ewromet.OrderRowsFileUtil;
import ru.ewromet.converter1.ColumnFactory;
import ru.ewromet.converter1.Controller1;
import ru.ewromet.converter1.TooltipTextFieldTableCell;
import ru.ewromet.converter2.parser.Attr;
import ru.ewromet.converter2.parser.Group;
import ru.ewromet.converter2.parser.Info;
import ru.ewromet.converter2.parser.QuotationInfo;
import ru.ewromet.converter2.parser.RadanAttributes;
import ru.ewromet.converter2.parser.RadanCompoundDocument;
import ru.ewromet.converter2.parser.SymFileParser;

import static java.util.Optional.ofNullable;
import static org.apache.commons.io.FilenameUtils.removeExtension;
import static ru.ewromet.Preferences.Key.PRODUCE_ORDER_TEMPLATE_PATH;
import static ru.ewromet.Utils.containsIgnoreCase;
import static ru.ewromet.Utils.equalsBy;
import static ru.ewromet.Utils.getFileExtension;
import static ru.ewromet.Utils.getWorkbook;

public class Controller3 extends Controller {

    @FXML
    private TableView<Compound> table1;

    @FXML
    private TableView<CompoundAggregation> table2;

    @FXML
    private MenuBar menuBar;

    private List<OrderRow> orderRows;
    private Integer orderNumber;
    private File[] files;

    private String orderFilePath;
    private File specFile;

    public void setOrderFilePath(String orderFilePath) {
        this.orderFilePath = orderFilePath;
    }

    public void setSpecFile(File specFile) {
        this.specFile = specFile;
    }

    public void setCompoundsPath(String compoundsDirPath) throws Exception {
        File compoundsDir = new File(compoundsDirPath);
        if (!compoundsDir.exists() || !compoundsDir.isDirectory()) {
            throw new Exception("Не найдена папка с компоновками");
        }
        files = compoundsDir.listFiles((file, name) -> {
            return name.toLowerCase().endsWith(".drg");
        });
        if (ArrayUtils.isEmpty(files)) {
            throw new Exception("В папке " + compoundsDir + " drg-файлы не найдены");
        }
    }

    @FXML
    private void initialize() {

        String template = preferences.get(PRODUCE_ORDER_TEMPLATE_PATH);
        if (StringUtils.isBlank(template)) {
            logError("Необходимо указать шаблон для заявки на производство в 'Меню' -> 'Указать шаблон заказа на производство'");
        } else {
            logMessage("Шаблон заказа на производство будет взят из " + template);
        }

        initializeMenu();
        initializeTable1();
        initializeTable2();
    }

    private void initializeMenu() {
        final Menu menu = new Menu();
        menu.setText("Меню");

        MenuItem produceOrderTemplateMenuItem = new MenuItem();
        produceOrderTemplateMenuItem.setText("Указать шаблон заказа на производство");
        produceOrderTemplateMenuItem.setOnAction(event -> {
            logArea.getItems().clear();

            chooseFileAndAccept(
                    new FileChooser.ExtensionFilter(
                            "Файлы с расширением '.xls' либо '.xlsx'", "*.xls", "*.xlsx"
                    ),
                    "Выбор файла",
                    file -> {
                        preferences.update(PRODUCE_ORDER_TEMPLATE_PATH, file.getAbsolutePath());
                        logMessage("Указан шаблон заказа на производство " + file);
                    }
            );
        });
        produceOrderTemplateMenuItem.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.CONTROL_DOWN));

        MenuItem saveItem = new MenuItem();
        saveItem.setText("Сохранить расход металла и сформировать заказ на производство");
        saveItem.setOnAction(event -> save());
        saveItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));

        menu.getItems().addAll(produceOrderTemplateMenuItem, saveItem);
        menuBar.getMenus().add(menu);
    }

    private void save() {
        // сохранить расход металла
        String fileExtension = getFileExtension(specFile);
        File sourceFile = new File(specFile.getAbsolutePath().replace(fileExtension, ".tmp" + fileExtension));
        try {
            FileUtils.copyFile(specFile, sourceFile);
            specFile.delete();
        } catch (IOException e) {
            throw new RuntimeException("Ошибка при копировании " + specFile + " в " + sourceFile);
        }

        Set<Pair<String, Double>> alreadyDefinedMaterials = new HashSet<>();
        Map<Pair<String, String>, String> materials = Controller1.getMATERIALS();

        try (FileInputStream inputStream = new FileInputStream(sourceFile);
             Workbook workbook = getWorkbook(inputStream, sourceFile.getAbsolutePath());
             OutputStream out = new FileOutputStream(specFile);
        ) {
            Sheet sheet = workbook.getSheet("расчет");

            boolean hearedRowFound = false;
            int metallCellNum = -1;
            int priceCellNum = -1;
            int materialCellNum = -1;
            int materialBrandCellNum = -1;
            int thinknessCellNum = -1;

            ROWS:
            for (int j = sheet.getFirstRowNum(); j <= sheet.getLastRowNum(); j++) {
                final Row row = sheet.getRow(j);
                if (row == null) {
                    continue;
                }
                if (!hearedRowFound) {
                    for (int k = row.getFirstCellNum(); k < row.getLastCellNum(); k++) {
                        Cell cell = row.getCell(k);
                        if (cell != null) {
                            String value;
                            try {
                                value = cell.getStringCellValue();
                                if (!hearedRowFound) {
                                    if (StringUtils.equals(value, "\u2116")) {
                                        hearedRowFound = true;
                                    }
                                } else if (StringUtils.containsIgnoreCase(value, "Расход металла, кв.м.")) {
                                    metallCellNum = k;
                                } else if (StringUtils.containsIgnoreCase(value, "Цена металла, руб/кг")) {
                                    priceCellNum = k;
                                } else if (StringUtils.containsIgnoreCase(value, "Материал")) {
                                    materialCellNum = k;
                                } else if (StringUtils.containsIgnoreCase(value, "Марка")) {
                                    materialBrandCellNum = k;
                                } else if (StringUtils.containsIgnoreCase(value, "Тощлина металла, мм") || StringUtils.containsIgnoreCase(value, "Толщина металла, мм")) {
                                    thinknessCellNum = k;
                                }
                                if (hearedRowFound
                                        && metallCellNum != -1
                                        && priceCellNum != -1
                                        && materialCellNum != -1
                                        && materialBrandCellNum != -1
                                        && thinknessCellNum != -1
                                        ) {
                                    break;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (hearedRowFound) {
                        continue ROWS;
                    } else {
                        continue;
                    }
                } else {
                    if (metallCellNum == -1) {
                        throw new RuntimeException("В файле " + specFile + " на вкладке 'расчет' в шапке таблицы не найдена колонка, содержащая фразу 'Расход металла, кв.м.'");
                    }
                    if (priceCellNum == -1) {
                        throw new RuntimeException("В файле " + specFile + " на вкладке 'расчет' в шапке таблицы не найдена колонка, содержащая фразу 'Цена металла, руб/кг'");
                    }
                    if (materialCellNum == -1) {
                        throw new RuntimeException("В файле " + specFile + " на вкладке 'расчет' в шапке таблицы не найдена колонка, содержащая фразу 'Материал'");
                    }
                    if (materialBrandCellNum == -1) {
                        throw new RuntimeException("В файле " + specFile + " на вкладке 'расчет' в шапке таблицы не найдена колонка, содержащая фразу 'Марка'");
                    }
                    if (thinknessCellNum == -1) {
                        throw new RuntimeException("В файле " + specFile + " на вкладке 'расчет' в шапке таблицы не найдена колонка, содержащая фразу 'Толщина металла, мм'");
                    }
                }

                Cell cell = row.getCell(materialCellNum);
                String material = null;
                if (cell != null) {
                    try {
                        material = cell.getStringCellValue();
                    } catch (Exception ignored) {
                        logError("В файле " + specFile + " на вкладке 'расчет' в строке таблицы #" + metallCellNum + " не найден вид металла");
                        continue;
                    }
                } else {
                    continue;
                }

                cell = row.getCell(materialBrandCellNum);
                String materialBrand = null;
                if (cell != null) {
                    try {
                        materialBrand = cell.getStringCellValue();
                    } catch (Exception ignored) {
                        logError("В файле " + specFile + " на вкладке 'расчет' в строке таблицы #" + metallCellNum + " не найдена марка металла");
                        continue;
                    }
                } else {
                    continue;
                }

                cell = row.getCell(thinknessCellNum);
                double thinkness = -1;
                if (cell != null) {
                    try {
                        thinkness = cell.getNumericCellValue();
                    } catch (Exception ignored) {
                        logError("В файле " + specFile + " на вкладке 'расчет' в строке таблицы #" + metallCellNum + " не найдена толщина металла");
                        continue;
                    }
                } else {
                    continue;
                }

                String foundMaterial = materials.get(Pair.of(material, materialBrand));

                if (foundMaterial == null) {
                    if (StringUtils.isNotBlank(material) && StringUtils.isNotBlank(materialBrand)) {
                        logError("Для материала " + material + " " + materialBrand + " не нашлось соответствия в таблице соответствия материалов");
                    }
                    continue;
                }

                for (CompoundAggregation aggregation : table2.getItems()) {
                    if (aggregation.getThickness() == thinkness && aggregation.getMaterial().equals(foundMaterial)) {
                        setValueToCell(row, priceCellNum, aggregation.getPrice());
                        if (alreadyDefinedMaterials.add(Pair.of(foundMaterial, thinkness))) {
                            setValueToCell(row, metallCellNum, aggregation.getTotalConsumption());
                            break;
                        }
                    }
                }
            }
            workbook.setForceFormulaRecalculation(true);
            workbook.write(out);
        } catch (Exception e) {
            logError("Ошибка при заполнении спецификации: " + e.getClass().getName() + ' ' + e.getMessage());
            return;
        } finally {
            if (!sourceFile.delete()) {
                sourceFile.deleteOnExit();
            }
        }

        createProduceOrder();

        logMessage("ДАННЫЕ СОХРАНЕНЫ");
    }

    private void createProduceOrder() {

        List<Compound> compounds = table1.getItems();

        if (CollectionUtils.isEmpty(compounds)) {
            return;
        }

        Iterator<Compound> compoundIterator = compounds.iterator();

        logMessage("Создание заказа на производство");

        String templatePath = preferences.get(PRODUCE_ORDER_TEMPLATE_PATH);
        File template = new File(templatePath);
        if (!template.exists()) {
            logError("Не найден шаблон заказа на производство по адресу " + template.getAbsolutePath());
            return;
        }

        File file = Paths.get(new File(orderFilePath).getParentFile().getPath(), template.getName()).toFile();
        Workbook wb = null;
        OutputStream os = null;
        try (FileInputStream inputStream = new FileInputStream(template);
             Workbook workbook = getWorkbook(inputStream, template.getAbsolutePath());
             OutputStream out = new FileOutputStream(file);
        ) {
            wb = workbook;
            os = out;

            Sheet sheet = workbook.getSheet("Заказ на производство");
            if (sheet == null) {
                logError("Не найдена вкладка 'Заказ на производство' в шаблоне");
                return;
            }

            boolean hearedRowFound = false;

            int posNumberCellNum = -1;
            int compoundNameCellNum = -1;
            int countCellNum = -1;
            int metallCellNum = -1;
            int sizeCellNum = -1;
            int ourMaterialCellNum = -1;
            int ownerMaterialCellNum = -1;

            Cell clientCell = null; // заказчик
            Cell bendingCell = null; // гибка
            Cell coloringCell = null; // окраска
            Cell wasteReturnCell = null; // возврат отходов
            Cell cuttingReturnCell = null; // возврат высечки

            ROWS:
            for (int lineNumber = sheet.getFirstRowNum(); lineNumber <= sheet.getLastRowNum(); lineNumber++) {
                final Row row = sheet.getRow(lineNumber);
                if (row == null) {
                    continue;
                }
                if (!hearedRowFound) {
                    for (int k = row.getFirstCellNum(); k < row.getLastCellNum(); k++) {
                        Cell cell = row.getCell(k);
                        if (cell != null) {
                            String value;
                            try {
                                value = cell.getStringCellValue();
                                if (!hearedRowFound) {
                                    if (StringUtils.equals(value, "\u2116")) {
                                        hearedRowFound = true;
                                        posNumberCellNum = k;
                                    } else {
                                        if (StringUtils.containsIgnoreCase(value, "Заказчик:")) {
                                            clientCell = cell;
                                        } else if (StringUtils.containsIgnoreCase(value, "Гибка")) {
                                            bendingCell = cell;
                                        } else if (StringUtils.containsIgnoreCase(value, "Окраска")) {
                                            coloringCell = cell;
                                        } else if (StringUtils.containsIgnoreCase(value, "Возврат отходов")) {
                                            wasteReturnCell = cell;
                                        } else if (StringUtils.containsIgnoreCase(value, "Высечки")) {
                                            cuttingReturnCell = cell;
                                        }
                                    }
                                } else if (StringUtils.containsIgnoreCase(value, "Металл")) {
                                    if (metallCellNum == -1) {
                                        metallCellNum = k;
                                    } else {
                                        ourMaterialCellNum = k;
                                        ownerMaterialCellNum = k + 1;
                                    }
                                } else if (StringUtils.containsIgnoreCase(value, "Программа")) {
                                    compoundNameCellNum = k;
                                } else if (StringUtils.containsIgnoreCase(value, "Кол-во")) {
                                    countCellNum = k;
                                } else if (StringUtils.containsIgnoreCase(value, "Размер заготовки")) {
                                    sizeCellNum = k;
                                }
                                if (hearedRowFound
                                        && metallCellNum != -1
                                        && compoundNameCellNum != -1
                                        && countCellNum != -1
                                        && sizeCellNum != -1
                                        && ourMaterialCellNum != -1
                                        && ownerMaterialCellNum != -1
                                        ) {
                                    break;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (hearedRowFound) {
                        lineNumber += 1;
                        continue ROWS;
                    } else {
                        continue;
                    }
                } else {
                    if (metallCellNum == -1) {
                        throw new RuntimeException("В шаблоне на вкладке 'Заявка на производство' в шапке таблицы не найдена колонка, содержащая фразу 'Металл'");
                    }
                    if (compoundNameCellNum == -1) {
                        throw new RuntimeException("В шаблоне на вкладке 'Заявка на производство' в шапке таблицы не найдена колонка, содержащая фразу 'Программа'");
                    }
                    if (countCellNum == -1) {
                        throw new RuntimeException("В шаблоне на вкладке 'Заявка на производство' в шапке таблицы не найдена колонка, содержащая фразу 'Кол-во'");
                    }
                    if (sizeCellNum == -1) {
                        throw new RuntimeException("В шаблоне на вкладке 'Заявка на производство' в шапке таблицы не найдена колонка, содержащая фразу 'Размер заготовки'");
                    }
                }

                if (!compoundIterator.hasNext()) {
                    break;
                }
                Compound compound = compoundIterator.next();
                setValueToCell(row, posNumberCellNum, compound.getPosNumber());
                setValueToCell(row, compoundNameCellNum, compound.getName());
                setValueToCell(row, countCellNum, compound.getN());
                setValueToCell(row, sizeCellNum, compound.getXr() + " x " + compound.getYr());

                ORDER_ROWS:
                for (OrderRow orderRow : orderRows) {
                    if (Double.compare(orderRow.getThickness(), compound.getThickness()) == 0) {
                        for (Map.Entry<Pair<String, String>, String> pairStringEntry : Controller1.getMATERIALS().entrySet()) {
                            if (Pair.of(orderRow.getOriginalMaterial(), orderRow.getMaterialBrand()).equals(pairStringEntry.getKey())
                                    && compound.getMaterial().equals(pairStringEntry.getValue())
                                    ) {
                                setValueToCell(row, metallCellNum, "#" + orderRow.getThickness() + " " + orderRow.getOriginalMaterial() + " " + orderRow.getMaterialBrand());
                                if (StringUtils.containsIgnoreCase(orderRow.getOwner(), "исполнитель")) {
                                    setValueToCell(row, ourMaterialCellNum, "V");
                                } else {
                                    setValueToCell(row, ownerMaterialCellNum, "V");
                                }

                                break ORDER_ROWS;
                            }
                        }
                    }
                }

                lineNumber++;
            }
            workbook.write(out);
        } catch (Exception e) {
            if (wb != null) {
                try {
                    wb.write(os);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
            logError("Ошибка при создании заказа на производство " + e.getMessage());
        }
    }

    public void fillTables() throws Exception {
        fillTable1();
        fillTable2();
    }

    private void fillTable2() {
        List<CompoundAggregation> oldItems = new ArrayList(table2.getItems());
        if (CollectionUtils.isNotEmpty(oldItems)) {
            table2.getItems().clear();
        }

        ObservableList<Compound> compounds = table1.getItems();
        if (CollectionUtils.isEmpty(compounds)) {
            return;
        }
        List<CompoundAggregation> compoundAggregations = new ArrayList<>();
        for (Compound compound : compounds) {
            CompoundAggregation compoundAggregation = new CompoundAggregation();

            compoundAggregation.setMaterial(compound.getMaterial());
            compoundAggregation.setMaterialBrand(compound.getMaterialBrand());
            compoundAggregation.setThickness(compound.getThickness());
            compoundAggregation.setSize(round(compound.getXr() / 1000 * compound.getYr() / 1000));
            compoundAggregation.setListsCount(compound.getN());
            compoundAggregation.setTotalConsumption(round(compoundAggregation.getListsCount() * compoundAggregation.getSize()));

            String material = compound.getMaterial();
            if (isAluminium(material)) {
                compoundAggregation.setMaterialDensity(2700);
            } else if (isBrass(material)) {
                compoundAggregation.setMaterialDensity(8800);
            } else if (isCopper(material)) {
                compoundAggregation.setMaterialDensity(8900);
            } else if (isSteelOrZintec(material)) {
                compoundAggregation.setMaterialDensity(7850);
            } else {
                compoundAggregation.setMaterialDensity(7850);
            }

            double totalConsumption = compoundAggregation.getTotalConsumption();
            double thickness = compoundAggregation.getThickness() / 1000;
            double materialDensity = compoundAggregation.getMaterialDensity();
            compoundAggregation.setWeight(round(totalConsumption * thickness * materialDensity));

            compoundAggregations.add(compoundAggregation);
        }

        List<Integer> indexesToDelete = new ArrayList<>();
        // aggregation
        for (int i = 0; i < compoundAggregations.size(); i++) {
            CompoundAggregation first = compoundAggregations.get(i);
            for (int j = 0; j < compoundAggregations.size(); j++) {
                if (i == j || indexesToDelete.contains(i) || indexesToDelete.contains(j)) {
                    continue;
                }
                CompoundAggregation second = compoundAggregations.get(j);
                if (needToAggregate(first, second)) {
                    first.setSize(first.getSize() + second.getSize());
                    first.setListsCount(first.getListsCount() + second.getListsCount());
                    first.setTotalConsumption(round(first.getTotalConsumption() + second.getTotalConsumption()));
                    first.setWeight(round(first.getWeight() + second.getWeight()));
                    indexesToDelete.add(j);
                }
            }
        }

        Collections.sort(indexesToDelete);
        Collections.reverse(indexesToDelete);
        indexesToDelete.forEach(index -> compoundAggregations.remove(index.intValue()));

        for (int i = 0; i < compoundAggregations.size(); i++) {
            compoundAggregations.get(i).setPosNumber(i + 1);
        }

        ObservableList<CompoundAggregation> items = FXCollections.observableList(compoundAggregations);
        table2.setItems(items);

        if (CollectionUtils.isNotEmpty(oldItems)) {
            for (CompoundAggregation oldItem : oldItems) {
                for (CompoundAggregation item : items) {
                    if (item.getPosNumber() == oldItem.getPosNumber()) {
                        item.setPrice(oldItem.getPrice());
                        item.setTotalPrice(round(item.getWeight() * item.getPrice()));
                        break;
                    }
                }
            }
            refreshTable(table2, null);
        }
    }

    private static double round(double value) {
        return Math.ceil(value * 1000) / 1000;
    }

    private boolean needToAggregate(CompoundAggregation first, CompoundAggregation second) {
        return first.getThickness() == second.getThickness()
                && first.getMaterialBrand().equals(second.getMaterialBrand())
                && first.getMaterial().equals(second.getMaterial());
    }

    private void fillTable1() throws Exception {
        List<RadanCompoundDocument> radanCompoundDocuments = Arrays.stream(files)
                .map(file -> {
                    try {
                        return SymFileParser.parse(file.getAbsolutePath());
                    } catch (ParserConfigurationException | SAXException | IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        String projectDirName = new File(orderFilePath).getParentFile().getName();

        try {
            orderNumber = Integer.parseUnsignedInt(projectDirName);
        } catch (NumberFormatException e) {
            throw new Exception("Ошибка при попытке получить номер заказа по названию папки заказа " + projectDirName, e);
        }

        orderRows = new OrderRowsFileUtil().restoreOrderRows(orderNumber);

        for (int i = 0, filesLength = files.length; i < filesLength; i++) {
            Compound compound = new Compound();
            compound.setPosNumber(i + 1);
            compound.setName(removeExtension(files[i].getName()));

            RadanCompoundDocument radanCompoundDocument = radanCompoundDocuments.get(i);
            RadanAttributes radanAttributes = radanCompoundDocument.getRadanAttributes();

            compound.setMaterial(getAttrValue(radanAttributes, "119"));
            compound.setThickness(Double.valueOf(getAttrValue(radanAttributes, "120")));
            compound.setXst((int) Math.ceil(Double.valueOf(getAttrValue(radanAttributes, "124"))));
            compound.setYst((int) Math.ceil(Double.valueOf(getAttrValue(radanAttributes, "125"))));
            compound.setN(Integer.valueOf(getAttrValue(radanAttributes, "137")));

            QuotationInfo quotationInfo = radanCompoundDocument.getQuotationInfo();
            compound.setXmin((int) Math.ceil(Double.valueOf(getInfoValue(quotationInfo, "1"))));
            compound.setYmin((int) Math.ceil(Double.valueOf(getInfoValue(quotationInfo, "2"))));

            setXYst(compound);

            calcCompoundEditableCells(compound);

            compound.setMaterialBrand(getBrand(orderRows, quotationInfo));

            table1.getItems().add(compound);
        }
    }

    private void calcCompoundEditableCells(Compound compound) {
        compound.setSk(round(compound.getXr() / 1000 * compound.getYr() / 1000));
        compound.setSo(round(compound.getN() * compound.getSk()));
    }


    private void setXYst(Compound compound) {
        String material = compound.getMaterial();
        double thickness = compound.getThickness();

        if (isMildSteelHkOrZintec(material)) {

            // Если Xmin > или = 80% от Xst, то Xr = Xst, иначе Xr=Xmin*1,2
            int xMin = compound.getXmin();
            int xSt = compound.getXst();
            compound.setXr(round(xMin >= xSt * 0.8 ? xSt : xMin * 1.2));

            // Если Ymin < (Yst/2), то Yr = Yst/2, иначе Yr = Yst
            int yMin = compound.getYmin();
            int ySt = compound.getYst();
            compound.setYr(round(yMin < ySt / 2 ? ySt / 2 : ySt));

        } else if (isMildSteelGk(material)) {

            // Если Xmin > или = 90% от Xst, то Xr = Xst, иначе Xr=Xmin*1,2
            int xMin = compound.getXmin();
            int xSt = compound.getXst();
            compound.setXr(round(xMin >= xSt * 0.9 ? xSt : xMin * 1.2));

            // Если Ymin < (Yst/2), то Yr = Yst/2, иначе Yr = Yst
            int yMin = compound.getYmin();
            int ySt = compound.getYst();
            compound.setYr(round(yMin < ySt / 2 ? ySt / 2 : ySt));

        } else if ((thickness > 2 && isAluminium(material)) || isStainlessSteelNoFoilNoShlif(material)) {

            // Если Xmin > или = 70% от Xst, то Xr = Xst, иначе Xr=Xmin*1,2
            int xMin = compound.getXmin();
            int xSt = compound.getXst();
            compound.setXr(round(xMin >= xSt * 0.7 ? xSt : xMin * 1.2));

            // Yr = Yst - всегда
            compound.setYr(round(compound.getYst()));

        } else if (
            // @formatter:off
                        (thickness <= 2 && isAluminium(material))
                                || (
                                thickness <= 0.8
                                        && (isStainlessSteelFoil(material) || isStainlessSteelShlif(material))
                                )
                    // @formatter:on
                ) {
            // Xr = Xst - всегда
            compound.setXr(compound.getXst());

            // Yr = Yst - всегда
            compound.setYr(compound.getYst());

        } else if (thickness >= 1 && (isStainlessSteelFoil(material) || isStainlessSteelShlif(material))) {

            // Если Xmin > или = 50% от Xst, то Xr = Xst, иначе Xr=Xmin*1,2
            int xMin = compound.getXmin();
            int xSt = compound.getXst();
            compound.setXr(xMin >= xSt / 2 ? xSt : xMin * 1.2);

            // Yr = Yst - всегда
            compound.setYr(compound.getYst());

        } else if (isBrass(material) || isCopper(material)) {

            // Если Xmin > или = 80% от Xst, то Xr = Xst, иначе Xr=Xmin*1,2
            int xMin = compound.getXmin();
            int xSt = compound.getXst();
            compound.setXr(xMin >= xSt * 0.8 ? xSt : xMin * 1.2);

            // Yr = Yst - всегда
            compound.setYr(compound.getYst());
        }
    }

    private static boolean isCopper(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Copper");
    }

    private static boolean isBrass(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Brass");
    }

    private static boolean isStainlessSteelFoil(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Stainless Steel Foil");
    }

    private static boolean isStainlessSteelShlif(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Stainless Steel Shlif");
    }

    private static boolean isSteelOrZintec(String material) {
        return isMildSteelHkOrZintec(material) || isStainlessSteel(material);
    }

    private static boolean isStainlessSteel(String material) {
        return isStainlessSteelShlif(material) || isStainlessSteelFoil(material) || isStainlessSteelNoFoilNoShlif(material);
    }

    private static boolean isAluminium(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Aluminium");
    }

    private static boolean isStainlessSteelNoFoilNoShlif(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Stainless Steel")
                && !StringUtils.containsIgnoreCase(material, "foil")
                && !StringUtils.containsIgnoreCase(material, "shlif");
    }

    private static boolean isMildSteelHkOrZintec(String material) {
        return isZintec(material) || isMildSteelHk(material);
    }

    private static boolean isZintec(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Zintec");
    }

    private static boolean isMildSteelHk(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Mild Steel hk");
    }

    private static boolean isMildSteelGk(String material) {
        return StringUtils.startsWithIgnoreCase(material, "Mild Steel gk");
    }

    private static String getAttrValue(RadanAttributes radanAttributes, String attrNum) {
        return ofNullable(radanAttributes)
                .map(RadanAttributes::getGroups)
                .orElse(Collections.emptyList())
                .stream()
                .filter(containsIgnoreCase(Group::getName, "Производство"))
                .map(Group::getAttrs)
                .flatMap(List::stream)
                .filter(equalsBy(Attr::getNum, attrNum))
                .map(Attr::getValue)
                .findFirst().get();
    }

    private static String getInfoValue(QuotationInfo quotationInfo, String infoNum) {
        return ofNullable(quotationInfo)
                .map(QuotationInfo::getInfos)
                .orElse(Collections.emptyList())
                .stream()
                .filter(equalsBy(Info::getNum, infoNum))
                .map(Info::getValue)
                .findFirst().get();
    }

    private static String getBrand(List<OrderRow> orderRows, QuotationInfo quotationInfo) throws Exception {

        String name = ofNullable(quotationInfo)
                .map(QuotationInfo::getInfos)
                .orElse(Collections.emptyList())
                .stream()
                .filter(equalsBy(Info::getNum, "4"))
                .map(Info::getSymbols)
                .flatMap(List::stream)
                .findFirst()
                .get().getName();

        for (OrderRow orderRow : orderRows) {
            if (StringUtils.startsWithIgnoreCase(orderRow.getDetailResultName(), name)) {
                return orderRow.getMaterialBrand();
            }
        }
        throw new Exception("Не найдена марка материала для " + name);
    }

    private void initializeTable1() {
        table1.setEditable(true);

        TableColumn<Compound, Integer> posNumberColumn = ColumnFactory.createColumn(
                "№", 30, "posNumber",
                TextFieldTableCell.forTableColumn(new IntegerStringConverter()), Compound::setPosNumber
        );

        posNumberColumn.setEditable(false);
        posNumberColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, String> nameColumn = ColumnFactory.createColumn(
                "Компоновка", 100, "name",
                column -> new TooltipTextFieldTableCell<>(), Compound::setName
        );

        nameColumn.setEditable(false);
        nameColumn.setStyle(ALIGNMENT_CENTER_LEFT);

        TableColumn<Compound, String> materialColumn = ColumnFactory.createColumn(
                "Материал", 50, "material",
                column -> new TooltipTextFieldTableCell<>(),
                Compound::setMaterial
        );

        materialColumn.setEditable(false);
        materialColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, Double> thicknessColumn = ColumnFactory.createColumn(
                "t, мм", 50, "thickness",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()), Compound::setThickness
        );

        thicknessColumn.setEditable(false);
        thicknessColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, Integer> nColumn = ColumnFactory.createColumn(
                "n, шт", 50, "n",
                TextFieldTableCell.forTableColumn(new IntegerStringConverter()), Compound::setN
        );

        nColumn.setEditable(false);
        nColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, Integer> ystColumn = ColumnFactory.createColumn(
                "Yst, мм", 50, "yst",
                TextFieldTableCell.forTableColumn(new IntegerStringConverter()), Compound::setYst
        );

        ystColumn.setEditable(false);
        ystColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, Integer> xstColumn = ColumnFactory.createColumn(
                "Xst, мм", 50, "xst",
                TextFieldTableCell.forTableColumn(new IntegerStringConverter()), Compound::setXst
        );

        xstColumn.setEditable(false);
        xstColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, Double> yrColumn = ColumnFactory.createColumn(
                "Yr, мм", 50, "yr",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()),
                (Compound compound, Double value) -> {
                    compound.setYr(value);
                    calcCompoundEditableCells(compound);
                    refreshTable(table1, null);
                    fillTable2();
                }
        );

        yrColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, Double> xrColumn = ColumnFactory.createColumn(
                "Xr, мм", 50, "xr",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()),
                (Compound compound, Double value) -> {
                    compound.setXr(value);
                    calcCompoundEditableCells(compound);
                    refreshTable(table1, null);
                    fillTable2();
                }
        );

        xrColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, Double> skColumn = ColumnFactory.createColumn(
                "Sk, кв. м.", 50, "sk",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()), Compound::setSk
        );

        skColumn.setEditable(false);
        skColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<Compound, Double> soColumn = ColumnFactory.createColumn(
                "So, кв. м.", 50, "so",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()), Compound::setSo
        );

        soColumn.setEditable(false);
        soColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        table1.getColumns().addAll(
                posNumberColumn,
                nameColumn,
                materialColumn,
                thicknessColumn,
                nColumn,
                ystColumn,
                xstColumn,
                yrColumn,
                xrColumn,
                skColumn,
                soColumn
        );

        table1.getColumns().forEach(column -> {
            final EventHandler oldOnEditCommitListener = column.getOnEditCommit();
            column.setOnEditCommit(event -> {
                Object oldValue = event.getOldValue();
                Object newValue = event.getNewValue();
                oldOnEditCommitListener.handle(event);

                final int posNumber = event.getRowValue().getPosNumber();
                logMessage(String.format(
                        "Изменение: колонка '%s', строка '%d', старое значение: '%s', новое значение: '%s'"
                        , event.getTableColumn().getText()
                        , posNumber
                        , oldValue
                        , newValue
                ));
                table1.requestFocus();
            });
        });
    }

    private void initializeTable2() {
        table2.setEditable(true);

        TableColumn<CompoundAggregation, Integer> posNumberColumn = ColumnFactory.createColumn(
                "№", 30, "posNumber",
                TextFieldTableCell.forTableColumn(new IntegerStringConverter()), CompoundAggregation::setPosNumber
        );

        posNumberColumn.setEditable(false);
        posNumberColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, String> materialColumn = ColumnFactory.createColumn(
                "Материал", 50, "material",
                column -> new TooltipTextFieldTableCell<>(),
                CompoundAggregation::setMaterial
        );

        materialColumn.setEditable(false);
        materialColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, String> materialBrandColumn = ColumnFactory.createColumn(
                "Марка материала", 50, "materialBrand",
                column -> new TooltipTextFieldTableCell<>(),
                CompoundAggregation::setMaterialBrand
        );

        materialBrandColumn.setEditable(false);
        materialBrandColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, Double> thicknessColumn = ColumnFactory.createColumn(
                "Толщина, мм", 50, "thickness",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()), CompoundAggregation::setThickness
        );

        thicknessColumn.setEditable(false);
        thicknessColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, Double> sizeColumn = ColumnFactory.createColumn(
                "Габариты листа, Xr x Yr", 50, "size",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()),
                CompoundAggregation::setSize
        );

        sizeColumn.setEditable(false);
        sizeColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, Integer> countColumn = ColumnFactory.createColumn(
                "Количество листов, шт", 50, "listsCount",
                TextFieldTableCell.forTableColumn(new IntegerStringConverter()), CompoundAggregation::setListsCount
        );

        countColumn.setEditable(false);
        countColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, Double> totalConsumptionColumn = ColumnFactory.createColumn(
                "Общий расход металла, кв.м.", 70, "totalConsumption",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()), CompoundAggregation::setTotalConsumption
        );

        totalConsumptionColumn.setEditable(false);
        totalConsumptionColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, Double> materialDensityColumn = ColumnFactory.createColumn(
                "Плотность материала, кг/м3", 50, "materialDensity",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()),
                (CompoundAggregation aggreration, Double value) -> {
                    aggreration.setMaterialDensity(value);
                    double totalConsumption = aggreration.getTotalConsumption();
                    double thickness = aggreration.getThickness() / 1000;
                    double materialDensity = aggreration.getMaterialDensity();
                    aggreration.setWeight(round(totalConsumption * thickness * materialDensity));
                    aggreration.setTotalPrice(round(aggreration.getWeight() * aggreration.getPrice()));
                    refreshTable(table2, null);
                }
        );

        materialDensityColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, Double> weightColumn = ColumnFactory.createColumn(
                "Масса материала, кг", 50, "weight",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()), CompoundAggregation::setWeight
        );

        weightColumn.setEditable(false);
        weightColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, Double> priceColumn = ColumnFactory.createColumn(
                "Цена за 1 кг, руб.", 50, "price",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()),
                (CompoundAggregation aggregation, Double value) -> {
                    aggregation.setPrice(value);
                    aggregation.setTotalPrice(round(aggregation.getWeight() * value));
                    refreshTable(table2, null);
                }
        );

        priceColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        TableColumn<CompoundAggregation, Double> totalPriceColumn = ColumnFactory.createColumn(
                "Общая стоимость, руб.", 50, "totalPrice",
                TextFieldTableCell.forTableColumn(new DoubleStringConverter()), CompoundAggregation::setTotalPrice
        );

        totalPriceColumn.setEditable(false);
        totalPriceColumn.setStyle(ALIGNMENT_BASELINE_CENTER);

        table2.getColumns().addAll(
                posNumberColumn,
                materialColumn,
                materialBrandColumn,
                thicknessColumn,
                sizeColumn,
                countColumn,
                totalConsumptionColumn,
                materialDensityColumn,
                weightColumn,
                priceColumn,
                totalPriceColumn
        );

        table2.getColumns().forEach(column -> {
            final EventHandler oldOnEditCommitListener = column.getOnEditCommit();
            column.setOnEditCommit(event -> {
                Object oldValue = event.getOldValue();
                Object newValue = event.getNewValue();
                oldOnEditCommitListener.handle(event);

                final int posNumber = event.getRowValue().getPosNumber();
                logMessage(String.format(
                        "Изменение: колонка '%s', строка '%d', старое значение: '%s', новое значение: '%s'"
                        , event.getTableColumn().getText()
                        , posNumber
                        , oldValue
                        , newValue
                ));
                table2.requestFocus();
            });
        });
    }
}