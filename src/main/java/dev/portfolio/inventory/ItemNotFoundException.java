package dev.portfolio.inventory;

public class ItemNotFoundException extends RuntimeException {
    public ItemNotFoundException(long id) { super("item " + id + " was not found"); }
}
