
package org.luwrain.app.mastodon;

import java.util.*;

import org.luwrain.core.*;
import org.luwrain.controls.*;
import org.luwrain.pim.mail.*;
import org.luwrain.app.base.*;

import org.luwrain.controls.WizardArea.Frame;
import org.luwrain.controls.WizardArea.WizardValues;

import static org.luwrain.core.DefaultEventResponse.*;

final class StartingLayout extends LayoutBase
{
    final App app;
    final WizardArea wizardArea;
    final Frame introFrame;

    StartingLayout(App app)
    {
	super(app);
	this.app = app;
	wizardArea = new WizardArea(getControlContext()) ;
	this.introFrame = wizardArea.newFrame()
	.addText(app.getStrings().wizardIntro())
	.addClickable(app.getStrings().wizardContinue(), this::onMailAddress);
	wizardArea.show(introFrame);
	setAreaLayout(wizardArea, null);
    }

    private boolean onMailAddress(WizardValues values)
    {
	final String mail = values.getText(0).trim();
	return true;
    }

}
