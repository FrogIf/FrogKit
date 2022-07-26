package sch.frog.kit;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import sch.frog.kit.common.CustomViewControl;
import sch.frog.kit.common.ExternalViewStruct;
import sch.frog.kit.common.LogKit;
import sch.frog.kit.common.util.StringUtil;
import sch.frog.kit.exception.GlobalExceptionThrower;
import sch.frog.kit.util.PluginUtil;
import sch.frog.kit.view.ExternalView;

import java.awt.*;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ResourceBundle;

public class MainController implements Initializable {

    private static MainController self;

    @FXML
    private TextArea msgText;

    @FXML
    private TabPane mainTabPane;

    private final LinkedList<CustomViewControl> views = new LinkedList<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        LogKit.init(new GlobalLogPrinter());
        MainControllerOperator.init(this);
        MainController.self = this;

        ObservableList<Tab> tabs = this.mainTabPane.getTabs();
        for (Tab tab : tabs) {
            CustomViewControl view = (CustomViewControl) tab.getContent();
            views.add(view);
        }

        List<ExternalViewStruct> externalViewStructs;
        try {
            externalViewStructs = PluginUtil.loadExternalViewStruct();
        } catch (Exception e) {
            GlobalExceptionThrower.throwException(e);
            return;
        }
        for (ExternalViewStruct externalViewStruct : externalViewStructs) {
            addExternalView(externalViewStruct);
        }

        this.mainTabPane.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            CustomViewControl oldView = (CustomViewControl) oldValue.getContent();
            CustomViewControl newView = (CustomViewControl) newValue.getContent();
            oldView.onHidden();
            newView.onShow();
        });

        ApplicationContext context = new ApplicationContext();
        context.addViews(this.views);
        for (CustomViewControl view : this.views) {
            view.afterLoad(context);
        }
    }

    private void addExternalView(ExternalViewStruct struct){
        ExternalView externalView = new ExternalView(struct);
        this.views.add(externalView);
    }

    private static final int MAX_LOG_ROWS = 100;

    private void outputLog(String message){
        if(StringUtil.isBlank(message)){ return; }
        if(StringUtil.isNotBlank(msgText.getText())){
            msgText.appendText("\n");
        }
        msgText.appendText(message);

        // control max rows
        ObservableList<CharSequence> paragraphs = msgText.getParagraphs();
        int rows = paragraphs.size();
        if(rows > MAX_LOG_ROWS){
            Iterator<CharSequence> iterator = paragraphs.iterator();
            int end = 0;
            while(iterator.hasNext() && rows > MAX_LOG_ROWS){
                CharSequence sequence = iterator.next();
                end += sequence.length() + 1;
                rows--;
            }
            msgText.deleteText(0, end);
        }
    }

    @FXML
    public void clearLog(){
        msgText.setText("");
    }

    public static void error(String message){
        Platform.runLater(() -> {
            self.outputLog(message);
            Toolkit.getDefaultToolkit().beep();
        });
    }

    public static void warn(String message){
        Platform.runLater(() -> {
            self.outputLog(message);
            Toolkit.getDefaultToolkit().beep();
        });
    }

    public static void info(String message){
        Platform.runLater(() -> {
            self.outputLog(message);
        });
    }

    public void onClose(){
        for (CustomViewControl view : views) {
            view.onClose();
        }
    }

    private static class GlobalLogPrinter implements LogKit.ILoggerPrinter {

        @Override
        public void info(String msg) {
            MainController.info(msg);
        }

        @Override
        public void warn(String msg) {
            MainController.warn(msg);
        }

        @Override
        public void error(String msg) {
            MainController.error(msg);
        }
    }

    void addView(CustomViewControl view, String title, boolean closable){
        if(StringUtil.isBlank(title)){
            LogKit.error("view title must be not null");
            return;
        }
        title = title.trim();
        ObservableList<Tab> tabs = this.mainTabPane.getTabs();
        for (Tab tab : tabs) {
            if(title.equals(tab.getText())){
                this.mainTabPane.getSelectionModel().select(tab);
                return;
            }
        }
        Tab tab = new Tab();
        tab.setClosable(closable);
        tab.setContent(view);
        tab.setText(title);
        tabs.add(tab);
        this.mainTabPane.getSelectionModel().select(tab);
    }
}