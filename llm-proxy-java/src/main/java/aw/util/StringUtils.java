package aw.util;

public class StringUtils {

  public static boolean isBlank(String text) {
    return text == null || text.trim().isEmpty();
  }
}
