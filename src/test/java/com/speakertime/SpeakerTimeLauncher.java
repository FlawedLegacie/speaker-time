package com.speakertime;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SpeakerTimeLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SpeakerTimePlugin.class);
		RuneLite.main(args);
	}
}
