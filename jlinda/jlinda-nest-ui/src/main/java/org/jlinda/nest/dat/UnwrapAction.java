package org.jlinda.nest.dat;

import org.esa.beam.framework.ui.command.CommandEvent;
import org.esa.beam.visat.actions.AbstractVisatAction;
import org.esa.snap.dat.graphbuilder.GraphBuilderDialog;
import org.esa.snap.rcp.SnapApp;

import java.io.File;

public class UnwrapAction extends AbstractVisatAction {
    
    @Override
    public void actionPerformed(CommandEvent event) {

        final GraphBuilderDialog dialog = new GraphBuilderDialog(new SnapApp.SnapContext(),
                "Unwrapping Tiles", "UnwrapOp", false);
        dialog.show();

        final File graphPath = GraphBuilderDialog.getInternalGraphFolder();
        final File graphFile =  new File(graphPath, "UnwrapTileGraph.xml");

        dialog.LoadGraph(graphFile);
    }
    
}