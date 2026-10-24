package org.upgradeguard.sentry.app.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import java.util.function.Consumer;

@Tag("sentry-mermaid")
@NpmPackage(value = "mermaid", version = "11.17.2")
@JsModule("./mermaid-diagram.js")
public class MermaidDiagram extends Component {
    public MermaidDiagram() {
        getElement().getStyle().set("width", "100%");
        getElement().setAttribute("role", "img");
        getElement().setAttribute("aria-label", "Diagram of the upgrade");
    }

    public void render(String source) {
        getElement().callJsFunction("render", source);
    }

    public void zoom(double delta) {
        getElement().callJsFunction("zoom", delta);
    }

    public void fit() {
        getElement().callJsFunction("fit");
    }

    public void fetchSvg(Consumer<String> receiver) {
        getElement().callJsFunction("svgText").then(String.class, receiver::accept);
    }

    public void onNodeSelected(Consumer<String> listener) {
        getElement().addEventListener("node-selected", event -> listener.accept(event.getEventData().get("event.detail.id").asString()))
                .addEventData("event.detail.id");
    }
}
