package order;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class Fixtures {
    private Fixtures() {
    }

    public static ObservableList<OrderRow> getData() {
        return FXCollections.observableArrayList(
                new OrderRow(1, "Деталь 1", 1, "материал", "марка материала", "окраска", "принадлежность", "гибка", 1, "комментарий"),
                new OrderRow(2, "Деталь 2", 2, "материал", "марка материала", "окраска", "принадлежность", "гибка", 2, "комментарий"),
                new OrderRow(3, "Деталь 3", 3, "материал", "марка материала", "окраска", "принадлежность", "гибка", 3, "комментарий"),
                new OrderRow(4, "Деталь 4", 4, "материал", "марка материала", "окраска", "принадлежность", "гибка", 4, "комментарий"),
                new OrderRow(5, "Деталь 5", 5, "материал", "марка материала", "окраска", "принадлежность", "гибка", 5, "комментарий"),
                new OrderRow(6, "Деталь 6", 6, "материал", "марка материала", "окраска", "принадлежность", "гибка", 6, "комментарий"),
                new OrderRow(7, "Деталь 7", 7, "материал", "марка материала", "окраска", "принадлежность", "гибка", 7, "комментарий"),
                new OrderRow(8, "Деталь 8", 8, "материал", "марка материала", "окраска", "принадлежность", "гибка", 8, "комментарий"),
                new OrderRow(9, "Деталь 9", 9, "материал", "марка материала", "окраска", "принадлежность", "гибка", 9, "комментарий"),
                new OrderRow(10, "Деталь 10", 10, "материал", "марка материала", "окраска", "принадлежность", "гибка", 10, "комментарий"),
                new OrderRow(11, "Деталь 11", 11, "материал", "марка материала", "окраска", "принадлежность", "гибка", 11, "комментарий"),
                new OrderRow(12, "Деталь 12", 12, "материал", "марка материала", "окраска", "принадлежность", "гибка", 12, "комментарий"),
                new OrderRow(13, "Деталь 13", 13, "материал", "марка материала", "окраска", "принадлежность", "гибка", 13, "комментарий"),
                new OrderRow(14, "Деталь 14", 14, "материал", "марка материала", "окраска", "принадлежность", "гибка", 14, "комментарий"),
                new OrderRow(15, "Деталь 15", 15, "материал", "марка материала", "окраска", "принадлежность", "гибка", 15, "комментарий"),
                new OrderRow(16, "Деталь 16", 16, "материал", "марка материала", "окраска", "принадлежность", "гибка", 16, "комментарий"),
                new OrderRow(17, "Деталь 17", 17, "материал", "марка материала", "окраска", "принадлежность", "гибка", 17, "комментарий"),
                new OrderRow(18, "Деталь 18", 18, "материал", "марка материала", "окраска", "принадлежность", "гибка", 18, "комментарий"),
                new OrderRow(19, "Деталь 19", 19, "материал", "марка материала", "окраска", "принадлежность", "гибка", 19, "комментарий"),
                new OrderRow(20, "Деталь 20", 20, "материал", "марка материала", "окраска", "принадлежность", "гибка", 20, "комментарий")
        );
    }
}
