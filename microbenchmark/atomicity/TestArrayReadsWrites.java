/**
 * @author fathi
 * 
 */

package atomicity;

// SB: Taken from ROCTET
public class TestArrayReadsWrites {

  private static boolean[] BOOL_VALS = { true, false };
  private static byte[] BYTE_VALS = { 60, 61, 62, 63, 64, 65, 66, 67, 68 };
  private static short[] SHORT_VALS = { 70, 71, 72, 73, 74, 75, 76, 77, 78 };

  /**
   * @param args
   */
  public static void main(String[] args) {

    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    System.out.println(" SHORT_VAL");
    System.out.println(testShort());
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    System.out.println(" BOOL_VAL");
    System.out.println(testBoolean());
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
  }

  private static boolean testBoolean() {
    int i;
    for (i = 0; i < BOOL_VALS.length; i++) {
      BOOL_VALS[i] = false;

    }
    return BOOL_VALS[0];
  }

  private static byte testByte() {
    int i;
    for (i = 0; i < BYTE_VALS.length; i++) {
      BYTE_VALS[i]++;

    }
    return BYTE_VALS[0];
  }

  private static short testShort() {
    int i;
    for (i = 0; i < SHORT_VALS.length; i++) {
      SHORT_VALS[i]++;

    }
    return SHORT_VALS[0];
  }
}
