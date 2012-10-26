package uk.ac.ox.well.indiana.tools;

import org.slf4j.Logger;
import uk.ac.ox.well.indiana.IndianaMain;
import uk.ac.ox.well.indiana.IndianaModule;
import uk.ac.ox.well.indiana.utils.arguments.ArgumentHandler;

public abstract class Tool implements IndianaModule {
    public Logger log = IndianaMain.getLogger();
    public String[] args;

    public Tool() {}

    public void init() {
        ArgumentHandler.parse(this, args);
    }

    public abstract int execute();
}
