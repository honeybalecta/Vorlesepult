package de.honeybalecta.vorlesepult;

import java.util.function.BooleanSupplier;

import kotlin.jvm.functions.Function1;

/**
 * Rückruf für die Engine während der Erzeugung: 1 = weiter, 0 = abbrechen.
 * Bewusst eine Java-Klasse: Die JNI-Seite von sherpa-onnx sucht per Reflexion die Methode
 * {@code invoke(float[]) -> Integer} direkt auf der Klasse des Rückrufs. Ein Kotlin-Lambda
 * (seit Kotlin 2 per invokedynamic erzeugt) besitzt nur {@code invoke(Object)} – die Suche
 * schlägt fehl, die schwebende Ausnahme bringt den Prozess um. Java erzeugt genau die gesuchte Signatur.
 */
public final class GenerationCallback implements Function1<float[], Integer> {
    private final BooleanSupplier cancelled;

    public GenerationCallback(BooleanSupplier cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public Integer invoke(float[] samples) {
        return cancelled.getAsBoolean() ? 0 : 1;
    }
}
