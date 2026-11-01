package com.patipp.sessions.api;

import com.patipp.sessions.internal.ChallengeService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ChallengeController {
    private final ChallengeService challenge;
    public ChallengeController(ChallengeService challenge) { this.challenge = challenge; }

    @PostMapping("/challenges/java-spring/enroll")
    public ChallengeDtos.Enrollment enroll() { return challenge.enroll(); }

    @GetMapping("/spaces/{spaceId}/challenge")
    public ChallengeDtos.Guide guide(@PathVariable UUID spaceId) { return challenge.guide(spaceId); }

    @PostMapping("/spaces/{spaceId}/challenge/days/{day}/start")
    public SessionDtos.SessionResponse start(@PathVariable UUID spaceId, @PathVariable int day,
                                             @RequestParam(defaultValue = "false") boolean timed) {
        return challenge.start(spaceId, day, timed);
    }
}
