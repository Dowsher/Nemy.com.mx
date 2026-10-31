import java.nio.file.*;
import java.util.*;
import java.io.*;
import org.pipalab.core.*;

/** Offline event-group audit using exactly the runtime Kotlin feature extractor. No network. */
public class ReplayDetector {
    static List<MotionPoint> load(Path path,int hz) throws Exception {
        var points=new ArrayList<MotionPoint>();long last=Long.MIN_VALUE/2;
        for(String line:Files.readAllLines(path).subList(1,Files.readAllLines(path).size())) {
            String[] r=line.split(",");long t=Long.parseLong(r[0]);
            if(t-last<1_000_000_000L/hz-2_000_000L)continue;
            points.add(new MotionPoint(t,Double.parseDouble(r[1]),Double.parseDouble(r[2]),Double.parseDouble(r[3])));last=t;
        }
        return points;
    }
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]);var templates=new ArrayList<MotionTemplate>();var groups=new LinkedHashMap<String,Boolean>();
        var rows=new ArrayList<String>();rows.add("event,positive,rate,valid_windows,candidates,held_out_positive_similarity,held_out_negative_similarity,calibrated_samples,stage_one_samples");
        for(String line:Files.readAllLines(dir.resolve("labels.csv"))) {
            String[] label=line.split(",");String id=label[0];boolean positive=label[1].equals("1");groups.put(id,positive);
            var features=MotionFingerprint.INSTANCE.windows(load(dir.resolve(id+".csv"),25));
            int stride=Math.max(1,(features.size()+15)/16);
            for(int i=0;i<features.size() && i/stride<16;i+=stride)templates.add(new MotionTemplate(id,positive,features.get(i)));
        }
        for(var group:groups.entrySet()) {
            String id=group.getKey();var heldOut=new MotionProfile(templates.stream().filter(t->!t.getGroup().equals(id)).toList());
            for(int hz:new int[]{50,25}) {
                var points=load(dir.resolve(id+".csv"),hz);var windows=MotionFingerprint.INSTANCE.windows(points);
                double pos=0,neg=0;for(var v:windows){var sim=heldOut.similarity(v);pos=Math.max(pos,sim.getFirst());neg=Math.max(neg,sim.getSecond());}
                var detector=new GestureDetector();detector.setProfile(heldOut);int candidates=0,calibrated=0,one=0;
                for(var p:points){if(detector.accel(p.getNs(),p.getX(),p.getY(),p.getZ())!=null)candidates++;if(detector.calibrated())calibrated++;if(detector.stage(p.getNs())==1)one++;}
                rows.add(id+","+group.getValue()+","+hz+","+windows.size()+","+candidates+","+pos+","+neg+","+calibrated+","+one);
            }
        }
        Files.write(dir.resolve("group-replay.csv"),rows);
        var profile=new MotionProfile(templates);
        Files.writeString(dir.resolve("summary.txt"),"Positive event groups: "+profile.getPositiveGroups()+"\nNegative event groups: "+profile.getNegativeGroups()+"\nTemplates: "+templates.size()+"\nAccuracy not estimable: only one negative group; retained segments are not continuous sessions.\nNo physiology-based discrimination fitted: temperature absent from labeled raw windows.\n");
        System.out.print(Files.readString(dir.resolve("summary.txt")));
    }
}
