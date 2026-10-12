package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.Locale;

/**
 * Reads and writes the {@code price} of a shop entry: a number of Safari
 * points, or the word {@code "auto"} for a Pokémon priced like the shop's
 * random ones. {@code "auto"} is kept in memory as
 * {@link SafariShopConfig.Entry#AUTO_PRICE} and written back as the word, so
 * a rewrite of the file never turns it into a number.
 *
 * <p>Put on the field with {@code @JsonAdapter}, so it applies wherever the
 * shop file is read, without registering it on a Gson instance.
 */
final class ShopPriceAdapter extends TypeAdapter<Integer> {

    /** The word that asks for a computed price, matched ignoring case. */
    static final String AUTO = "auto";

    /** Writes the price as a number, or as {@link #AUTO}. */
    @Override
    public void write(JsonWriter out, Integer price) throws IOException {
        if (price != null && price == SafariShopConfig.Entry.AUTO_PRICE) {
            out.value(AUTO);
        } else {
            out.value(price == null ? 0 : price);
        }
    }

    /**
     * Reads a number or {@link #AUTO}. Any other text fails the read of the
     * whole file, which then falls back to its defaults without being
     * overwritten, and the log names the bad value: guessing a price for a
     * typo could sell something for free.
     */
    @Override
    public Integer read(JsonReader in) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NULL) {
            in.nextNull();
            // A null field of an int would fail Gson's reflective set: 0 like a missing key.
            return 0;
        }
        if (token == JsonToken.STRING) {
            String text = in.nextString();
            if (AUTO.equals(text.trim().toLowerCase(Locale.ROOT))) {
                return SafariShopConfig.Entry.AUTO_PRICE;
            }
            throw new JsonParseException("Shop price must be a number or \"" + AUTO + "\", got \"" + text + "\"");
        }
        return in.nextInt();
    }
}
