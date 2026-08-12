package data.missions.starterpack_bench;

import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

/**
 * The StarterPack refit bench.
 *
 * This file is compiled by Janino at runtime, which supports only a subset of Java, so it does
 * nothing but hand off to the mod's jar where the full language is available. Keep it this dumb --
 * every line added here is a line that has to survive Janino.
 */
public class MissionDefinition implements MissionDefinitionPlugin {

	public void defineMission(MissionDefinitionAPI api) {
		starterpack.bench.LoadoutBench.defineMission(api);
	}
}
