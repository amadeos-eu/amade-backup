package eu.amadeos.apps.backup.domain;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A backup or sync job as the user set it up. A backup copies {@code sources} into the restic repository at
 * {@code target}; a home backup has the home folder as its only source and leaves out what is not in
 * {@code homeAreas}. A sync copies each of its {@code pairs}.
 *
 * @param excludes patterns to leave out (restic's syntax: {@code *.iso}, {@code **}{@code /node_modules},
 *                 absolute paths), also used by syncs
 */
public record Job(String id, String name, JobKind kind, List<Path> sources, Target target, Set<HomeArea> homeAreas,
                  List<String> excludes, BackupOptions options, KeepPolicy keep, Schedule schedule,
                  List<FolderPair> pairs, SyncOptions sync) {
    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        name = name == null || name.isBlank() ? id : name.strip();
        sources = List.copyOf(sources);
        homeAreas = homeAreas.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(homeAreas));
        excludes = excludes.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        options = options == null ? BackupOptions.DEFAULT : options;
        keep = keep == null ? KeepPolicy.DEFAULT : keep;
        schedule = schedule == null ? Schedule.MANUAL : schedule;
        pairs = List.copyOf(pairs);
        sync = sync == null ? SyncOptions.DEFAULT : sync;
        if (kind == JobKind.BACKUP) Objects.requireNonNull(target, "target");
    }

    /** A backup of the whole home folder, without what is not in {@code areas}. */
    public static Job home(String id, String name, Path home, Target target, Set<HomeArea> areas, List<String> excludes) {
        return new Job(id, name, JobKind.BACKUP, List.of(home), target, areas, excludes, BackupOptions.DEFAULT,
                KeepPolicy.DEFAULT, Schedule.DEFAULT, List.of(), null);
    }

    public static Job backup(String id, String name, List<Path> sources, Target target) {
        return new Job(id, name, JobKind.BACKUP, sources, target, Set.of(), List.of(), BackupOptions.DEFAULT,
                KeepPolicy.DEFAULT, Schedule.DEFAULT, List.of(), null);
    }

    public static Job sync(String id, String name, List<FolderPair> pairs, SyncOptions options) {
        return new Job(id, name, JobKind.SYNC, List.of(), null, Set.of(), List.of(), null, null, Schedule.MANUAL,
                pairs, options);
    }

    /** True for the backup of the home folder with switchable areas. */
    public boolean isHome() {
        return kind == JobKind.BACKUP && !homeAreas.isEmpty();
    }

    public Job withName(String newName) {
        return new Job(id, newName, kind, sources, target, homeAreas, excludes, options, keep, schedule, pairs, sync);
    }

    public Job withSources(List<Path> newSources) {
        return new Job(id, name, kind, newSources, target, homeAreas, excludes, options, keep, schedule, pairs, sync);
    }

    public Job withTarget(Target newTarget) {
        return new Job(id, name, kind, sources, newTarget, homeAreas, excludes, options, keep, schedule, pairs, sync);
    }

    public Job withHomeAreas(Set<HomeArea> areas) {
        return new Job(id, name, kind, sources, target, areas, excludes, options, keep, schedule, pairs, sync);
    }

    public Job withExcludes(List<String> newExcludes) {
        return new Job(id, name, kind, sources, target, homeAreas, newExcludes, options, keep, schedule, pairs, sync);
    }

    public Job withOptions(BackupOptions newOptions) {
        return new Job(id, name, kind, sources, target, homeAreas, excludes, newOptions, keep, schedule, pairs, sync);
    }

    public Job withKeep(KeepPolicy newKeep) {
        return new Job(id, name, kind, sources, target, homeAreas, excludes, options, newKeep, schedule, pairs, sync);
    }

    public Job withSchedule(Schedule newSchedule) {
        return new Job(id, name, kind, sources, target, homeAreas, excludes, options, keep, newSchedule, pairs, sync);
    }

    public Job withPairs(List<FolderPair> newPairs) {
        return new Job(id, name, kind, sources, target, homeAreas, excludes, options, keep, schedule, newPairs, sync);
    }

    public Job withSync(SyncOptions newSync) {
        return new Job(id, name, kind, sources, target, homeAreas, excludes, options, keep, schedule, pairs, newSync);
    }
}
