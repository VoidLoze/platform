package diplom.platform.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ApiController {

	@GetMapping("/api/v1/health")
	public Map<String, String> health() {
		return Map.of("status", "ok");
	}
}
