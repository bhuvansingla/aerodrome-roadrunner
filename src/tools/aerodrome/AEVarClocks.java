  package tools.aerodrome;

  import acme.util.count.Counter;
  import rr.state.ShadowVar;
  import tools.aerodrome.AEVectorClock;

  public class AEVarClocks implements ShadowVar {
      public final AEVectorClock read;
      public final AEVectorClock write;
      public final AEVectorClock readcheck;

      private static Counter AEVarClocks = new Counter("AEVarClocks", "triplet Objects");

      public AEVarClocks(int dim) {
          read = new AEVectorClock(dim);
          write = new AEVectorClock(dim);
          readcheck = new AEVectorClock(dim);
          AEVarClocks.inc();
      }

      @Override
      public String toString() {
          return "R" + read + " - " + "W" + write + "RCHK"+readcheck;
      }
  }
