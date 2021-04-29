  package tools.aerodrome;

  import acme.util.count.Counter;
  import rr.state.ShadowVar;
  import tools.util.VectorClock;;

  public class ADVarClocks implements ShadowVar {
      public final VectorClock read;
      public final VectorClock write;
      public final VectorClock readcheck;

      private static Counter ADVarClocks = new Counter("AD", "Other (Ignore)");

      public ADVarClocks(int dim) {
          read = new VectorClock(dim);
          write = new VectorClock(dim);
          readcheck = new VectorClock(dim);
          ADVarClocks.inc();
      }

      @Override
      public String toString() {
          return "R" + read + " - " + "W" + write + "RCHK"+readcheck;
      }
  }
