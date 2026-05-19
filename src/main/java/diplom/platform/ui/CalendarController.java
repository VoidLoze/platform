package diplom.platform.ui;

import diplom.platform.application.CalendarService;
import diplom.platform.infrastructure.security.PlatformUser;
import diplom.platform.infrastructure.security.SecurityUtils;
import diplom.platform.ui.dto.CalendarTaskDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {
    private final CalendarService calendarService;

    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('STUDENT')")
    public List<CalendarTaskDto> myCalendar(
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to
    ) {
        PlatformUser me = SecurityUtils.requireCurrentUser();
        return calendarService.getStudentCalendar(me.id(), from, to);
    }

    @GetMapping("/teacher")
    @PreAuthorize("hasRole('TEACHER')")
    public List<CalendarTaskDto> teacherCalendar() {
        PlatformUser me = SecurityUtils.requireCurrentUser();
        return calendarService.getTeacherCalendar(me.id());
    }
}
